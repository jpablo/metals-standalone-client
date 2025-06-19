package scala.meta.metals.standalone

import java.nio.file.{Files, Path, Paths}
import java.util.logging.{Logger, Level, ConsoleHandler, SimpleFormatter}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Main entry point for the standalone Metals MCP client.
 * 
 * This application launches Metals language server in minimal mode and
 * enables the MCP server for AI assistant integration without requiring
 * a full IDE client like VS Code or Cursor.
 */
object Main {
  
  case class Config(
    projectPath: Path = Paths.get(".").toAbsolutePath.normalize(),
    verbose: Boolean = false
  )

  def main(args: Array[String]): Unit = {
    val config = parseArgs(args)
    setupLogging(config.verbose)
    
    val app = new MetalsLight(config.projectPath, config.verbose)
    
    // Handle shutdown gracefully
    sys.addShutdownHook {
      println("\nShutting down...")
      app.shutdown()
    }
    
    val exitCode = app.run()
    sys.exit(exitCode)
  }

  private def parseArgs(args: Array[String]): Config = {
    args.toList match {
      case Nil => Config()
      case "--help" :: _ | "-h" :: _ =>
        printUsage()
        sys.exit(0)
      case "--verbose" :: Nil | "-v" :: Nil => 
        Config(verbose = true)
      case "--verbose" :: path :: Nil => 
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case "-v" :: path :: Nil => 
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "--verbose" :: Nil => 
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "-v" :: Nil => 
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: Nil => 
        Config(Paths.get(path).toAbsolutePath.normalize())
      case invalid =>
        println(s"Error: Invalid arguments: ${invalid.mkString(" ")}")
        printUsage()
        sys.exit(1)
    }
  }

  private def printUsage(): Unit = {
    println("""
      |Metals Standalone MCP Client
      |
      |Usage: metals-light [OPTIONS] [PROJECT_PATH]
      |
      |Arguments:
      |  PROJECT_PATH    Path to Scala project directory (default: current directory)
      |
      |Options:
      |  -v, --verbose   Enable verbose logging
      |  -h, --help      Show this help message
      |
      |Examples:
      |  metals-light                    # Use current directory
      |  metals-light /path/to/project   # Use specific project path
      |  metals-light --verbose .        # Enable verbose logging
      |
      |This tool starts Metals language server with MCP server enabled,
      |allowing AI assistants to interact with your Scala project.
      |""".stripMargin)
  }

  private def setupLogging(verbose: Boolean): Unit = {
    val rootLogger = Logger.getLogger("")
    rootLogger.setLevel(if (verbose) Level.INFO else Level.WARNING)
    
    // Remove default handlers and add custom one
    rootLogger.getHandlers.foreach(rootLogger.removeHandler)
    
    val handler = new ConsoleHandler() {
      setOutputStream(System.out) // Send to stdout instead of stderr
    }
    handler.setLevel(if (verbose) Level.INFO else Level.WARNING)
    handler.setFormatter(new SimpleFormatter() {
      override def format(record: java.util.logging.LogRecord): String = {
        if (verbose) {
          s"[${record.getLevel}] ${record.getMessage}\n"
        } else {
          s"${record.getMessage}\n"
        }
      }
    })
    
    rootLogger.addHandler(handler)
  }
}

/**
 * Core application logic for the standalone Metals client.
 */
class MetalsLight(projectPath: Path, verbose: Boolean) {
  private val logger = Logger.getLogger(classOf[MetalsLight].getName)
  
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  private var launcher: Option[MetalsLauncher] = None
  private var lspClient: Option[LspClient] = None
  private var metalsClient: Option[MetalsClient] = None
  private var mcpMonitor: Option[McpMonitor] = None

  def run(): Int = {
    try {
      println("🚀 Starting Metals standalone MCP client...")
      
      val metalsLauncher = new MetalsLauncher(projectPath)
      launcher = Some(metalsLauncher)
      
      // Validate project
      if (!metalsLauncher.validateProject()) {
        println("❌ Project validation failed")
        return 1
      }
      
      // Launch Metals process
      println("📦 Launching Metals language server...")
      val process = metalsLauncher.launchMetals() match {
        case Some(p) => p
        case None =>
          println("❌ Failed to launch Metals")
          return 1
      }
      
      // Create LSP client
      val client = new LspClient(process)
      lspClient = Some(client)
      
      // Block and wait for the future to complete
      import scala.concurrent.duration._
      import scala.util.Try
      Try {
        scala.concurrent.Await.result(
          client.start().flatMap { _ =>
            println("🔗 Connected to Metals LSP server")
            
            // Create and initialize Metals client
            val metals = new MetalsClient(projectPath, client)
            metalsClient = Some(metals)
            
            metals.initialize().flatMap { success =>
              if (success) {
                println("✅ Metals language server initialized")
                
                // Create MCP monitor
                val monitor = new McpMonitor(projectPath)
                mcpMonitor = Some(monitor)
                
                // Wait for MCP server
                println("⏳ Waiting for MCP server to start...")
                monitor.waitForMcpServer().flatMap {
                  case Some(mcpUrl) =>
                    // Print connection info
                    monitor.printConnectionInfo(mcpUrl)
                    
                    // Monitor health until interrupted
                    monitor.monitorMcpHealth(mcpUrl)
                  case None =>
                    println("❌ MCP server failed to start")
                    Future.successful(false)
                }
              } else {
                println("❌ Failed to initialize Metals")
                Future.successful(false)
              }
            }
          },
          Duration.Inf
        )
        0
      }.recover {
        case e: InterruptedException =>
          println("\n🛑 Interrupted by user")
          0
        case e: Exception =>
          println(s"❌ Application failed: ${e.getMessage}")
          if (verbose) {
            e.printStackTrace()
          }
          1
      }.get
      
    } catch {
      case e: InterruptedException =>
        println("\n🛑 Interrupted by user")
        0
      case e: Exception =>
        println(s"❌ Unexpected error: ${e.getMessage}")
        if (verbose) {
          e.printStackTrace()
        }
        1
    }
  }

  def shutdown(): Unit = {
    println("🔄 Shutting down components...")
    
    // Shutdown in reverse order
    metalsClient.foreach { client =>
      try {
        client.shutdown()
        println("✅ Metals client shutdown")
      } catch {
        case e: Exception =>
          logger.warning(s"Error shutting down Metals client: ${e.getMessage}")
      }
    }
    
    lspClient.foreach { client =>
      try {
        client.shutdown()
        println("✅ LSP client shutdown")
      } catch {
        case e: Exception =>
          logger.warning(s"Error shutting down LSP client: ${e.getMessage}")
      }
    }
    
    launcher.foreach { launcher =>
      try {
        launcher.shutdown()
        println("✅ Metals process shutdown")
      } catch {
        case e: Exception =>
          logger.warning(s"Error shutting down Metals launcher: ${e.getMessage}")
      }
    }
    
    println("👋 Goodbye!")
  }
}