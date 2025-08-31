package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Uses Kyo-based components for full effect-based architecture.
  */
class MetalsLight(projectPath: Path, initTimeout: FiniteDuration):
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  private val launcher                             = new MetalsLauncherK(projectPath)
  private var metalsProcess: Option[Process]       = None
  private var lspClient: Option[LspClient]         = None
  private var metalsClient: Option[MetalsClient]   = None

  def run()(using Frame): Unit < (Async & Abort[Throwable] & Scope & Sync) =
    Abort.catching[Throwable] {
      Scope.ensure {
        Sync.defer(shutdown())
      }.andThen {
        startApplication()
      }
    }

  private def startApplication()(using Frame): Unit < (Async & Abort[Throwable] & Sync) =
    for
      _ <- Sync.defer(println("🚀 Starting Metals standalone MCP client..."))
      _ <- validateProject()
      _ <- Sync.defer(println("📦 Launching Metals language server..."))
      process <- launchMetals()
      _ <- startLspClient(process)
      _ <- initializeMetals()
      _ <- startMcpMonitoring()
    yield ()

  private def validateProject()(using Frame): Unit < (Async & Abort[Throwable] & Sync) =
    launcher.validateProject().map { valid =>
      if !valid then
        throw new RuntimeException("❌ Project validation failed")
    }

  private def launchMetals()(using Frame): Process < (Sync & Abort[Throwable]) =
    for
      process <- launcher.launchMetals()
      _ <- Sync.defer { metalsProcess = Some(process) }
    yield process

  private def startLspClient(process: Process)(using Frame): Unit < (Async & Abort[Throwable] & Sync) =
    for
      jProcess <- Sync.defer {
        // Access the underlying JProcess - this is needed until LspClient is also ported to Kyo
        val field = process.getClass.getDeclaredField("process")
        field.setAccessible(true)
        field.get(process).asInstanceOf[java.lang.Process]
      }
      client <- Sync.defer {
        val c = new LspClient(jProcess)
        lspClient = Some(c)
        c
      }
      _ <- Async.fromFuture(client.start())
      _ <- Sync.defer(println("🔗 Connected to Metals LSP server"))
    yield ()

  private def initializeMetals()(using Frame): Unit < (Async & Abort[Throwable] & Sync) =
    for
      client <- Sync.defer {
        lspClient.getOrElse(throw new RuntimeException("LSP client not started"))
      }
      metals <- Sync.defer {
        val m = new MetalsClient(projectPath, client, initTimeout)  
        metalsClient = Some(m)
        m
      }
      _ <- Sync.defer(println("Initializing Metals language server..."))
      initialized <- Async.fromFuture(metals.initialize())
      _ <- if initialized then
        Sync.defer(println("✅ Metals language server initialized"))
      else
        Abort.fail(new RuntimeException("❌ Failed to initialize Metals"))
    yield ()

  private def startMcpMonitoring()(using Frame): Unit < (Async & Abort[Throwable] & Sync) =
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
