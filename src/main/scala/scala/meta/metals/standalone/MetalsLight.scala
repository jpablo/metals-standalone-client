package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Uses Kyo-based components for full effect-based architecture.
  */
class MetalsLight(projectPath: Path, initTimeout: FiniteDuration):
  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val launcher                             = new MetalsLauncherK(projectPath)
  private val launcher2                             = new MetalsLauncher(projectPath)
  private var metalsProcess: Option[Process]       = None
  private var lspClient: Option[LspClient]         = None
  private var metalsClient: Option[MetalsClient]   = None

  def run(): Int < (Async & Abort[Throwable] & Scope & Sync) =
    Abort.catching[Throwable] {
      Scope.ensure {
        Sync.defer(shutdown())
      }.andThen {
        startApplication()
      }
    }

  private def startApplication(): Int < (Async & Abort[Throwable] & Sync) =
    for
      _ <- Log.info("🚀 Starting Metals standalone MCP client...")
      valid <- launcher.validateProject()
//      valid = launcher2.validateProject()
      _ <- Log.info(s"${if valid then "valid" else "invalid"} configuration found")
      _ <- Log.info("📦 Launching Metals language server...")
      process <- launcher.launchMetals()
//      process = launcher2.launchMetals()
      _ <- Log.info(s"process: $process")
//      _ <- Async.fromFuture(startLspClient(process))
      _ <- Async.fromFuture(startLspClient2(process))
      _ <- initializeMetals()
      _ <- startMcpMonitoring()
    yield 0


  private def startLspClient(process: Process): Future[Unit] =
    // Access the underlying JProcess - this is needed until LspClient is also ported to Kyo
    val field = process.getClass.getDeclaredField("process")
    field.setAccessible(true)
    val jProcess = field.get(process).asInstanceOf[java.lang.Process]
    startLspClient2(jProcess)

  private def startLspClient2(process: java.lang.Process): Future[Unit] =
    val c = new LspClient(process)
    lspClient = Some(c)
    c.start()


  private def initializeMetals(): Unit < (Async & Abort[Throwable] & Sync) =
    for
      client <- Sync.defer {
        lspClient.getOrElse(throw new RuntimeException("LSP client not started"))
      }
      _ = println(s"--- 0 ---- $client")
      metals <- Sync.defer {
        val m = new MetalsClient(projectPath, client, initTimeout)
        metalsClient = Some(m)
        m
      }
      _ <- Log.info("Initializing Metals language server...")
      initialized <- Async.fromFuture(metals.initialize())
      _ <- if initialized then
        Log.info("-------------- ✅ Metals language server initialized")
      else
        Abort.fail(new RuntimeException("❌ Failed to initialize Metals"))
    yield ()

  private def initializeMetals2() =
    for
      client <- Sync.defer {
        lspClient.getOrElse(throw new RuntimeException("LSP client not started"))
      }
      _ = println(s"--- 0 ---- $client")
      metals <- Sync.defer {
        val m = new MetalsClient(projectPath, client, initTimeout)
        metalsClient = Some(m)
        m
      }
      _ <- Log.info("Initializing Metals language server...")
      initialized <- Async.fromFuture(metals.initialize())
      _ <- if initialized then
        Log.info("-------------- ✅ Metals language server initialized")
      else
        Abort.fail(new RuntimeException("❌ Failed to initialize Metals"))
    yield ()

  private def startMcpMonitoring(): Unit < (Async & Abort[Throwable] & Sync) =
    for
      monitor <- Sync.defer(new McpMonitor(projectPath))
      _ <- Sync.defer(println("⏳ Waiting for MCP server to start..."))
      mcpUrl <- Async.fromFuture(monitor.waitForMcpServer()).map {
        case Some(url) => url
        case None => throw new RuntimeException("❌ MCP server failed to start")
      }
      _ <- Sync.defer(monitor.printConnectionInfo(mcpUrl))
      _ <- Async.fromFuture(monitor.monitorMcpHealth(mcpUrl)).map(_ => ())
    yield ()

  private def shutdown(): Unit =
    println("🔄 Shutting down components...")

    // Shutdown in reverse order
    metalsClient.foreach { client =>
      try client.shutdown()
      catch case e: Exception =>
        java.lang.System.err.println(s"Error shutting down Metals client: ${e.getMessage}")
    }

    lspClient.foreach { client =>
      try client.shutdown()
      catch case e: Exception =>
        java.lang.System.err.println(s"Error shutting down LSP client: ${e.getMessage}")
    }

    metalsProcess.foreach { process =>
      try
        // Run the shutdown through the Frame/effects system
        import kyo.AllowUnsafe.embrace.danger
        val shutdownEffect = launcher.shutdown(process)
        val _ = Sync.Unsafe.run(Log.let(Log.live)(shutdownEffect))
      catch case e: Exception =>
        java.lang.System.err.println(s"Error shutting down Metals process: ${e.getMessage}")
    }

    println("👋 Goodbye!")
