package scala.meta.metals.standalone

import io.circe._
import io.circe.parser._
import sttp.client3._

import java.nio.file.{Files, Path}
import java.util.logging.Logger
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

/**
 * Monitor and manage MCP server configuration.
 * Watches for configuration files and tests server health.
 */
class McpMonitor(projectPath: Path)(using ExecutionContext) {
  private val logger = Logger.getLogger(classOf[McpMonitor].getName)

  private val configPaths = Seq(
    projectPath.resolve(".metals/mcp.json"),
    projectPath.resolve(".cursor/mcp.json"),
    projectPath.resolve(".vscode/mcp.json")
  )

  private val httpClient = SimpleHttpClient()

  def findMcpConfig(): Option[Path] = {
    configPaths.find { configPath =>
      if (Files.exists(configPath)) {
        logger.info(s"Found MCP config at: $configPath")
        true
      } else false
    }
  }

  def parseMcpConfig(configPath: Path): Option[Json] = {
    try {
      val content = Files.readString(configPath)
      parse(content) match {
        case Right(json) => Some(json)
        case Left(error) =>
          logger.severe(s"Error parsing MCP config $configPath: $error")
          None
      }
    } catch {
      case e: Exception =>
        logger.severe(s"Error reading MCP config $configPath: ${e.getMessage}")
        None
    }
  }

  def extractMcpUrl(config: Json): Option[String] = {
    try {
      val cursor = config.hcursor

      // Try different configuration structures
      val mcpServers = cursor.downField("mcpServers").as[Json]
        .orElse(cursor.downField("servers").as[Json])
        .getOrElse(Json.obj())

      val serversCursor = mcpServers.hcursor

      // Look for metals-metals server
      val metalsConfig = serversCursor.downField("metals-metals").as[Json]
        .orElse {
          // Try alternative naming - find any key containing "metals"
          mcpServers.asObject.flatMap { obj =>
            obj.keys.find(_.toLowerCase.contains("metals"))
              .flatMap(key => serversCursor.downField(key).as[Json].toOption)
          }.toRight("No metals server found")
        }

      metalsConfig match {
        case Right(metalsConf) =>
          val configCursor = metalsConf.hcursor

          // Extract URL from transport configuration
          configCursor.downField("transport").downField("url").as[String]
            .orElse {
              // Try direct URL field
              configCursor.downField("url").as[String]
            }
            .toOption.map { url =>
              logger.info(s"Found MCP URL: $url")
              url
            }

        case Left(_) =>
          logger.info("No metals server configuration found")
          None
      }
    } catch {
      case e: Exception =>
        logger.severe(s"Error extracting MCP URL: ${e.getMessage}")
        None
    }
  }

  def testMcpConnection(url: String, timeoutSeconds: Int = 5): Future[Boolean] = {
    Future {
      try {
        // Try to connect to the base URL (without /sse endpoint)
        val baseUrl = url.stripSuffix("/sse").stripSuffix("/")

        val request = basicRequest
          .get(uri"$baseUrl")
          .header("User-Agent", "metals-standalone-client/0.1.0")
          .readTimeout(timeoutSeconds.seconds)

        val response = httpClient.send(request)

        // Some HTTP errors are acceptable (like 404) - server is responding
        response.code.code < 500
      } catch {
        case e: Exception =>
          logger.info(s"MCP connection test failed: ${e.getMessage}")
          false
      }
    }
  }

  def waitForMcpServer(timeoutSeconds: Int = 60): Future[Option[String]] = {
    val startTime = System.currentTimeMillis()
    val endTime = startTime + (timeoutSeconds * 1000)

    def checkForServer(): Future[Option[String]] = {
      val currentTime = System.currentTimeMillis()

      if (currentTime >= endTime) {
        logger.warning(s"MCP server did not start within $timeoutSeconds seconds")
        Future.successful(None)
      } else {
        // Log progress every 10 seconds
        val elapsed = (currentTime - startTime) / 1000
        if (elapsed > 0 && elapsed % 10 == 0) {
          logger.info(s"Still waiting for MCP server... (${elapsed}s elapsed)")
        }

        findMcpConfig() match {
          case Some(configPath) =>
            parseMcpConfig(configPath) match {
              case Some(config) =>
                extractMcpUrl(config) match {
                  case Some(mcpUrl) =>
                    testMcpConnection(mcpUrl).flatMap { isHealthy =>
                      if (isHealthy) {
                        logger.info(s"MCP server is ready at: $mcpUrl")
                        Future.successful(Some(mcpUrl))
                      } else {
                        logger.info("MCP config found but server not responding yet")
                        // Wait 1 second and try again
                        Future {
                          Thread.sleep(1000)
                        }.flatMap(_ => checkForServer())
                      }
                    }
                  case None =>
                    // Config exists but no URL found, wait and try again
                    Future {
                      Thread.sleep(1000)
                    }.flatMap(_ => checkForServer())
                }
              case None =>
                // Config exists but parsing failed, wait and try again
                Future {
                  Thread.sleep(1000)
                }.flatMap(_ => checkForServer())
            }
          case None =>
            // No config found yet, wait and try again
            Future {
              Thread.sleep(1000)
            }.flatMap(_ => checkForServer())
        }
      }
    }

    logger.info("Waiting for MCP server to start...")
    checkForServer()
  }

  def getClaudeCommand(mcpUrl: String): String = {
    val projectName = projectPath.getFileName.toString
    val serverName = s"$projectName-metals"
    s"claude mcp add --transport sse $serverName $mcpUrl"
  }

  def printConnectionInfo(mcpUrl: String): Unit = {
    println()
    println("🎉 MCP server is running!")
    println(s"URL: $mcpUrl")
    println()
    println("To connect with Claude Code, run:")
    println(s"  ${getClaudeCommand(mcpUrl)}")
    println()
    println("Press Ctrl+C to stop the server...")
  }

  def monitorMcpHealth(mcpUrl: String, checkIntervalSeconds: Int = 30): Future[Boolean] = {
    def healthCheck(): Future[Boolean] = {
      testMcpConnection(mcpUrl).flatMap { isHealthy =>
        if (isHealthy) {
          // Wait for the check interval, then check again
          Future {
            Thread.sleep(checkIntervalSeconds * 1000)
          }.flatMap(_ => healthCheck())
        } else {
          logger.warning("MCP server appears to be down")
          Future.successful(false)
        }
      }.recover {
        case _: InterruptedException =>
          logger.info("Health monitoring stopped by user")
          true // Return true to indicate graceful stop
        case e =>
          logger.severe(s"Error monitoring MCP health: ${e.getMessage}")
          false
      }
    }

    healthCheck()
  }
}
