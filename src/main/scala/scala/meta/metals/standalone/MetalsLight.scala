package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import scala.concurrent.ExecutionContext
import java.lang.{Process => JProcess}

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Uses the working Future-based components and bridges them to Kyo via Async.fromFuture.
  * This approach maintains the robust error handling and lifecycle management from the
  * main branch while keeping the Kyo interface.
  */
class MetalsLight(projectPath: Path):
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  private val launcher                             = new MetalsLauncher(projectPath)
  private var lspClient: Option[LspClient]         = None
  private var metalsClient: Option[MetalsClient]   = None

  def run()(using Frame): Unit < (Async & Abort[Throwable] & Scope) =
    Abort.catching[Throwable] {
      Scope.ensure {
        Sync.defer(shutdown())
      }.andThen {
        startApplication()
      }
    }

  private def startApplication()(using Frame): Unit < (Async & Abort[Throwable]) =
    for
      _ <- Sync.defer(println("🚀 Starting Metals standalone MCP client..."))
      _ <- validateProject()
      _ <- Sync.defer(println("📦 Launching Metals language server..."))
      process <- launchMetals()
      _ <- startLspClient(process)
      _ <- initializeMetals()
      _ <- startMcpMonitoring()
    yield ()

  private def validateProject()(using Frame): Unit < (Async & Abort[Throwable]) =
    Async.fromFuture {
      scala.concurrent.Future {
        if !launcher.validateProject() then
          throw new RuntimeException("❌ Project validation failed")
      }
    }

  private def launchMetals()(using Frame): JProcess < (Async & Abort[Throwable]) =
    Async.fromFuture {
      scala.concurrent.Future {
        launcher.launchMetals() match
          case Some(process) => process
          case None => throw new RuntimeException("❌ Failed to launch Metals")
      }
    }

  private def startLspClient(process: JProcess)(using Frame): Unit < (Async & Abort[Throwable]) =
    for
      client <- Sync.defer {
        val c = new LspClient(process)
        lspClient = Some(c)
        c
      }
      _ <- Async.fromFuture(client.start())
      _ <- Sync.defer(println("🔗 Connected to Metals LSP server"))
    yield ()

  private def initializeMetals()(using Frame): Unit < (Async & Abort[Throwable]) =
    for
      client <- Sync.defer {
        lspClient.getOrElse(throw new RuntimeException("LSP client not started"))
      }
      metals <- Sync.defer {
        val m = new MetalsClient(projectPath, client)  
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

  private def startMcpMonitoring()(using Frame): Unit < (Async & Abort[Throwable]) =
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

    try launcher.shutdown()
    catch case e: Exception =>
      java.lang.System.err.println(s"Error shutting down launcher: ${e.getMessage}")

    println("👋 Goodbye!")
