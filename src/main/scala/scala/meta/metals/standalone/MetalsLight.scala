package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Uses Kyo-based components for full effect-based architecture.
  */
class MetalsLight(projectPath: Path, initTimeout: kyo.Duration):
  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val launcher                             = new MetalsLauncherK(projectPath)
  private var metalsProcess: Option[Process]       = None
  private var lspClient: Option[LspClient]         = None
  private var metalsClient: Option[MetalsClient]   = None

  def run(): Int < (Async & Abort[Throwable] & Scope) =
    Scope.ensure(shutdown()).andThen(startApplication())

  private def startApplication(): Int < (Async & Abort[Throwable]) =
    for
      _ <- Log.info("🚀 Starting Metals standalone MCP client...")
      valid <- launcher.validateProject()
      _ <- Log.debug(s"${if valid then "valid" else "invalid"} configuration found")
      _ <- Log.info("📦 Launching Metals language server...")
      process <- launcher.launchMetals()
      _ <- Log.info(s"process: $process")
      _ <- startLspClient(process)
      _ <- initializeMetals()
      _ <- startMcpMonitoring()
    yield 0


  private def startLspClient(process: java.lang.Process): Unit < (Async & Abort[Throwable]) =
    val c = new LspClient(process)
    lspClient = Some(c)
    c.start()


  private def initializeMetals(): Unit < (Async & Abort[Throwable]) =
    for
      client <- Sync.defer(lspClient.getOrElse(throw new RuntimeException("LSP client not started")))
      metals <- Sync.defer {
        val m = new MetalsClient(projectPath, client, initTimeout)
        metalsClient = Some(m)
        m
      }
      _ <- Log.info("Initializing Metals language server...")
      initialized <- metals.initialize()
      _ <- if initialized then
        Log.info("-------------- ✅ Metals language server initialized")
      else
        Abort.fail(new RuntimeException("❌ Failed to initialize Metals"))
    yield ()


  private def startMcpMonitoring(): Unit < (Async & Abort[Throwable]) =
    for
      monitor = McpMonitor(projectPath)
      _ <- Sync.defer(println("⏳ Waiting for MCP server to start..."))
      mcpUrl <- Async.fromFuture(monitor.waitForMcpServer()).map {
        case Some(url) => url
        case None => throw new RuntimeException("❌ MCP server failed to start")
      }
      _ <- Sync.defer(monitor.printConnectionInfo(mcpUrl))
      _ <- Async.fromFuture(monitor.monitorMcpHealth(mcpUrl)).map(_ => ())
    yield ()

  private def shutdown(): Unit < (Async & Abort[Throwable]) =
    for
      _ <- metalsClient.map(c => c.shutdown()).getOrElse(Sync.defer(()))
      _ <- lspClient.map(c => c.shutdown()).getOrElse(Sync.defer(()))
      _ <- launcher.shutdown()
      _ <- Log.info("👋 Goodbye!")
    yield ()
