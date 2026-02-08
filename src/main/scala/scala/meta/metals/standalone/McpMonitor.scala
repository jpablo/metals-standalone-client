package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import sttp.client3.*

import java.nio.file.{Files, Path}
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import java.util.logging.Logger
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}

/** Monitor and manage MCP server configuration. Watches for configuration files and tests server health.
  */
class McpMonitor(projectPath: Path)(using ExecutionContext):
  private val logger = Logger.getLogger(classOf[McpMonitor].getName)

  private val configPaths = Seq(
    projectPath.resolve(".metals/mcp.json"),
    projectPath.resolve(".cursor/mcp.json"),
    projectPath.resolve(".vscode/mcp.json")
  )

  private val httpClient = SimpleHttpClient()
  private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(r =>
    val thread = new Thread(r, "mcp-monitor-scheduler")
    thread.setDaemon(true)
    thread
  )

  private def delay(duration: FiniteDuration): Future[Unit] =
    val promise = Promise[Unit]()
    scheduler.schedule(
      new Runnable:
        def run(): Unit = promise.success(())
      ,
      duration.toMillis,
      TimeUnit.MILLISECONDS
    )
    promise.future

  def findMcpConfig(): Option[Path] =
    configPaths.find { configPath =>
      if Files.exists(configPath) then
        logger.info(s"Found MCP config at: $configPath")
        true
      else false
    }

  def parseMcpConfig(configPath: Path): Option[Json] =
    try
      val content = Files.readString(configPath)
      parse(content) match
        case Right(json) => Some(json)
        case Left(error) =>
          logger.severe(s"Error parsing MCP config $configPath: $error")
          None
    catch
      case e: Exception =>
        logger.severe(s"Error reading MCP config $configPath: ${e.getMessage}")
        None

  def extractMcpUrl(config: Json): Option[String] =
    try
      val cursor = config.hcursor

      // Try different configuration structures
      val mcpServers = cursor
        .downField("mcpServers")
        .as[Json]
        .orElse(cursor.downField("servers").as[Json])
        .getOrElse(Json.obj())

      val serversCursor = mcpServers.hcursor

      // Look for metals-metals server
      val metalsConfig = serversCursor
        .downField("metals-metals")
        .as[Json]
        .orElse {
          // Try alternative naming - find any key containing "metals"
          mcpServers.asObject
            .flatMap { obj =>
              obj.keys
                .find(_.toLowerCase.contains("metals"))
                .flatMap(key => serversCursor.downField(key).as[Json].toOption)
            }
            .toRight("No metals server found")
        }

      metalsConfig match
        case Right(metalsConf) =>
          val configCursor = metalsConf.hcursor

          // Extract URL from transport configuration
          configCursor
            .downField("transport")
            .downField("url")
            .as[String]
            .orElse {
              // Try direct URL field
              configCursor.downField("url").as[String]
            }
            .toOption
            .map { url =>
              logger.info(s"Found MCP URL: $url")
              url
            }

        case Left(_) =>
          logger.info("No metals server configuration found")
          None
    catch
      case e: Exception =>
        logger.severe(s"Error extracting MCP URL: ${e.getMessage}")
        None

  def testMcpConnection(url: String, timeoutSeconds: Int = 5): Future[Boolean] =
    Future(blocking {
      try
        // Try to connect to the base URL (without /sse or /mcp endpoint)
        val baseUrl = url.stripSuffix("/sse").stripSuffix("/mcp").stripSuffix("/")

        val request = basicRequest
          .get(uri"$baseUrl")
          .header("User-Agent", "metals-standalone-client/0.1.0")
          .readTimeout(timeoutSeconds.seconds)

        val response = httpClient.send(request)

        // Some HTTP errors are acceptable (like 404) - server is responding
        response.code.code < 500
      catch
        case e: Exception =>
          logger.info(s"MCP connection test failed: ${e.getMessage}")
          false
    })

  def waitForMcpServer(timeoutSeconds: Int = 60): Future[Option[String]] =
    val startTime = System.currentTimeMillis()
    val endTime   = startTime + (timeoutSeconds * 1000)

    def checkForServer(): Future[Option[String]] =
      val currentTime = System.currentTimeMillis()

      if currentTime >= endTime then
        logger.warning(s"MCP server did not start within $timeoutSeconds seconds")
        Future.successful(None)
      else
        // Log progress every 10 seconds
        val elapsed = (currentTime - startTime) / 1000
        if elapsed > 0 && elapsed % 10 == 0 then logger.info(s"Still waiting for MCP server... (${elapsed}s elapsed)")

        findMcpConfig() match
          case Some(configPath) =>
            parseMcpConfig(configPath) match
              case Some(config) =>
                extractMcpUrl(config) match
                  case Some(mcpUrl) =>
                    testMcpConnection(mcpUrl).flatMap { isHealthy =>
                      if isHealthy then
                        logger.info(s"MCP server is ready at: $mcpUrl")
                        Future.successful(Some(mcpUrl))
                      else
                        logger.info("MCP config found but server not responding yet")
                        // Wait 1 second and try again
                        delay(1.second).flatMap(_ => checkForServer())
                    }
                  case None         =>
                    // Config exists but no URL found, wait and try again
                    delay(1.second).flatMap(_ => checkForServer())
              case None         =>
                // Config exists but parsing failed, wait and try again
                delay(1.second).flatMap(_ => checkForServer())
          case None             =>
            // No config found yet, wait and try again
            delay(1.second).flatMap(_ => checkForServer())

    logger.info("Waiting for MCP server to start...")
    checkForServer()

  def getClaudeCommand(mcpUrl: String): String =
    val projectName = projectPath.getFileName.toString
    val serverName  = s"$projectName-metals"
    val transport   = if mcpUrl.endsWith("/mcp") then "http" else "sse"
    s"claude mcp add --transport $transport $serverName $mcpUrl"

  def printConnectionInfo(mcpUrl: String, serverVersion: Option[String] = None): Unit =
    val versionSuffix = serverVersion.map(v => s" (Metals v$v)").getOrElse("")
    println()
    println(s"🎉 MCP server is running!$versionSuffix")
    println(s"URL: $mcpUrl")
    println()
    println("To connect with Claude Code, run:")
    println(s"  ${getClaudeCommand(mcpUrl)}")
    println()
    println("Press Ctrl+C to stop the server...")

  def monitorMcpHealth(mcpUrl: String, checkIntervalSeconds: Int = 30): Future[Boolean] =
    def healthCheck(): Future[Boolean] =
      testMcpConnection(mcpUrl)
        .flatMap { isHealthy =>
          if isHealthy then
            // Wait for the check interval, then check again
            delay(checkIntervalSeconds.seconds).flatMap(_ => healthCheck())
          else
            logger.warning("MCP server appears to be down")
            Future.successful(false)
        }
        .recover {
          case _: InterruptedException =>
            logger.info("Health monitoring stopped by user")
            true // Return true to indicate graceful stop
          case e =>
            logger.severe(s"Error monitoring MCP health: ${e.getMessage}")
            false
        }

    healthCheck()

  def shutdown(): Unit =
    scheduler.shutdown()
    try httpClient.close()
    catch
      case e: Exception =>
        logger.warning(s"Error shutting down MCP HTTP client: ${e.getMessage}")
