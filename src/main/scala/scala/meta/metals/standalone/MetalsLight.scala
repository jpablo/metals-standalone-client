package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/** Kyo-based runner for the standalone Metals MCP client using direct style. */
class MetalsLight(projectPath: Path, initTimeout: kyo.Duration):
  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val launcher = new MetalsLauncherK(projectPath)
  private var metalsProcess: Option[java.lang.Process] = None
  private var lspClient: Option[LspClient] = None
  private val metalsClient = AtomicRef.init(None: Option[MetalsClient])


  def run(): Int < (Async & Abort[Throwable] & Scope & Sync) =
    Abort.catching[Throwable] {
      Scope.ensure(Var.run(None)(shutdown())).andThen(startApplication())
    }

  private def startApplication(): Int < (Async & Abort[Throwable] & Sync) =
    direct:
      Log.info("🚀 Starting Metals standalone MCP client...").now
      val valid = launcher.validateProject().now
      Log.debug(s"${if valid then "valid" else "invalid"} configuration found").now
      Log.info("📦 Launching Metals language server...").now

      val process = launcher.launchMetals().now
      Log.info(s"process: $process").now

      startLspClient(process).now
      initializeMetals().now
      startMcpMonitoring().now
      0

  private def startLspClient(process: java.lang.Process): Unit < (Async & Abort[Throwable] & Sync) =
    val c = new LspClient(process)
    lspClient = Some(c)
    c.start()

  private def initializeMetals(): Unit < (Async & Abort[Throwable] & Sync) =
    direct:
      val client = Abort.catching[RuntimeException](lspClient.getOrElse(throw new RuntimeException("LSP client not started"))).now
      val metals = new MetalsClient(projectPath, client, initTimeout)
      metalsClient.map(_.set(Some(metals))).now
      Log.info("Initializing Metals language server...").now
      val initialized = metals.initialize().now
      if initialized then
        Log.info("-------------- ✅ Metals language server initialized").now
      else
        Abort.fail(new RuntimeException("❌ Failed to initialize Metals")).now


  private def startMcpMonitoring(): Unit < (Async & Abort[Throwable] & Sync) =
    direct {
      val monitor = new McpMonitor(projectPath)
      Log.debug("⏳ Waiting for MCP server to start...").now
      val maybeUrl = Async.fromFuture(monitor.waitForMcpServer()).now
      val mcpUrl = maybeUrl match
        case Some(url) => url
        case None      => Abort.fail(new RuntimeException("❌ MCP server failed to start")).now
      monitor.printConnectionInfo(mcpUrl)
      Async.fromFuture(monitor.monitorMcpHealth(mcpUrl)).map(_ => ()).now
    }

  private def shutdown(): Unit < (Async & Abort[Throwable] & Sync) =
    direct:
      metalsClient.map(_.get).now.foreach(_.shutdown().now)
      lspClient.map(_.shutdown()).getOrElse(Sync.defer(())).now
      launcher.shutdown().now
      Log.info("👋 Goodbye!").now
