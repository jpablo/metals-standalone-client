package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import kyo.*
import kyo.Log
import sttp.client3.*
import scala.concurrent.duration.*

import java.nio.file.{Files, Path}

/** Kyo port of McpMonitor: discovers MCP config and waits for the server to be healthy. */
class McpMonitorK(projectPath: Path)(using Frame):
  // Logging via kyo.Log

  private val configPaths = Seq(
    projectPath.resolve(".metals/mcp.json"),
    projectPath.resolve(".cursor/mcp.json"),
    projectPath.resolve(".vscode/mcp.json")
  )

  private val httpClient = SimpleHttpClient()

  def findMcpConfig(): Option[Path] < Sync =
    Sync.defer(configPaths.find(p => Files.exists(p))).flatMap {
      case Some(p) => Log.info(s"Found MCP config at: $p").andThen(Sync.defer(Some(p)))
      case None    => Sync.defer(None)
    }

  def parseMcpConfig(configPath: Path): Option[Json] < Sync =
    Sync.defer {
      try
        val content = Files.readString(configPath)
        parse(content).toOption
      catch
        case e: Exception =>
          // Log error and return None
          scala.Console.err.println(s"Error reading MCP config $configPath: ${e.getMessage}")
          None
    }

  def extractMcpUrl(config: Json): Option[String] < Sync =
    Sync.defer {
      try
        val cursor      = config.hcursor
        val mcpServers  = cursor.downField("mcpServers").as[Json].toOption.orElse(cursor.downField("servers").as[Json].toOption).getOrElse(Json.obj())
        val serversCur  = mcpServers.hcursor
        val metalsEntry =
          serversCur.downField("metals-metals").as[Json].toOption.orElse {
            mcpServers.asObject.flatMap { obj =>
              obj.keys.find(_.toLowerCase.contains("metals")).flatMap(k => serversCur.downField(k).as[Json].toOption)
            }
          }
        metalsEntry.flatMap { metalsConf =>
          val c = metalsConf.hcursor
          c.downField("transport").downField("url").as[String].toOption.orElse(c.downField("url").as[String].toOption)
        }.map(identity)
      catch
        case _: Exception => None
    }

  def testMcpConnection(url: String, timeoutSeconds: Int = 5): Boolean < Sync =
    Sync.defer {
      try
        val baseUrl  = url.stripSuffix("/sse").stripSuffix("/")
        val request  = basicRequest.get(uri"$baseUrl").header("User-Agent", "metals-standalone-client/0.1.0").readTimeout(timeoutSeconds.seconds)
        val response = httpClient.send(request)
        response.code.code < 500
      catch
        case e: Exception =>
          // It's expected to fail for invalid URLs used in tests
          false
    }

  def waitForMcpServer(timeoutSeconds: Int = 60): Option[String] < (Async & Sync) =
    def loop(remaining: Int): Option[String] < (Async & Sync) =
      if remaining <= 0 then Log.warn(s"MCP server did not start within $timeoutSeconds seconds").andThen(Sync.defer(None))
      else
        findMcpConfig().flatMap {
          case Some(conf) =>
            parseMcpConfig(conf).flatMap {
              case Some(json) =>
                extractMcpUrl(json).flatMap {
                  case Some(url) =>
                    testMcpConnection(url).flatMap { ok =>
                      if ok then Log.info(s"MCP server is ready at: $url").andThen(Sync.defer(Some(url)))
                      else Async.sleep(1.second).andThen(loop(remaining - 1))
                    }
                  case None      => Async.sleep(1.second).andThen(loop(remaining - 1))
                }
              case None      => Async.sleep(1.second).andThen(loop(remaining - 1))
            }
          case None        => Async.sleep(1.second).andThen(loop(remaining - 1))
        }

    loop(timeoutSeconds)

  def getClaudeCommand(mcpUrl: String): String =
    val projectName = projectPath.getFileName.toString
    val serverName  = s"$projectName-metals"
    s"claude mcp add --transport sse $serverName $mcpUrl"

  def printConnectionInfo(mcpUrl: String): Unit < Sync =
    Sync.defer {
      println()
      println("🎉 MCP server is running!")
      println(s"URL: $mcpUrl")
      println()
      println("To connect with Claude Code, run:")
      println(s"  ${getClaudeCommand(mcpUrl)}")
      println()
      println("Press Ctrl+C to stop the server...")
    }

  def monitorMcpHealth(mcpUrl: String, checkIntervalSeconds: Int = 30): Boolean < (Async & Sync) =
    def health(): Boolean < (Async & Sync) =
      testMcpConnection(mcpUrl).flatMap { ok =>
        if ok then Async.sleep(checkIntervalSeconds.seconds).andThen(health())
        else Log.warn("MCP server appears to be down").andThen(Sync.defer(false))
      }
    health()
