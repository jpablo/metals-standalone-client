package scala.meta.metals.standalone

import java.nio.file.{Path, Paths}
import java.util.logging.{ConsoleHandler, Level, Logger, SimpleFormatter}
import scala.concurrent.{ExecutionContext, Future}

/** Main entry point for the standalone Metals MCP client.
  *
  * This application launches Metals language server in minimal mode and enables the MCP server for AI assistant
  * integration without requiring a full IDE client like VS Code or Cursor.
  */
object Main extends kyo.KyoApp:
  private val logger = Logger.getLogger(Main.getClass.getName)

  case class Config(
      projectPath: Path = Paths.get(".").toAbsolutePath.normalize(),
      verbose: Boolean = false
  )

  run {
    import kyo.*
    val config = parseArgs(args.toArray)
    for
      _ <- Sync.defer(setupLogging(config.verbose))
      // Run the Kyo-native Metals flow
      _ <- new MetalsLightK(config.projectPath, config.verbose).run()
    yield ()
  }

  private def parseArgs(args: Array[String]): Config =
    args.toList match
      case Nil                              => Config()
      case "--help" :: _ | "-h" :: _        =>
        printUsage()
        sys.exit(0)
      case "--verbose" :: Nil | "-v" :: Nil =>
        Config(verbose = true)
      case "--verbose" :: path :: Nil       =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case "-v" :: path :: Nil              =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "--verbose" :: Nil       =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "-v" :: Nil              =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: Nil                      =>
        Config(Paths.get(path).toAbsolutePath.normalize())
      case invalid                          =>
        logger.severe(s"Error: Invalid arguments: ${invalid.mkString(" ")}")
        printUsage()
        sys.exit(1)

  private def printUsage(): Unit =
    logger.info("""
                  |Metals Standalone MCP Client
                  |
                  |Usage: metals-standalone-client [OPTIONS] [PROJECT_PATH]
                  |   or: sbt run [OPTIONS] [PROJECT_PATH]
                  |
                  |Arguments:
                  |  PROJECT_PATH    Path to Scala project directory (default: current directory)
                  |
                  |Options:
                  |  -v, --verbose   Enable verbose logging
                  |  -h, --help      Show this help message
                  |
                  |Examples:
                  |  metals-standalone-client                    # Use current directory
                  |  metals-standalone-client /path/to/project   # Use specific project path
                  |  metals-standalone-client --verbose .        # Enable verbose logging
                  |  sbt "run --verbose /my/scala/project"       # Run via SBT
                  |
                  |This tool starts Metals language server with MCP server enabled,
                  |allowing AI assistants to interact with your Scala project.
                  |""".stripMargin)

  private def setupLogging(verbose: Boolean): Unit =
    val rootLogger = Logger.getLogger("")
    rootLogger.setLevel(if verbose then Level.INFO else Level.WARNING)

    rootLogger.getHandlers.foreach(rootLogger.removeHandler)

    val handler = new ConsoleHandler():
      setOutputStream(System.out)

    handler.setLevel(if verbose then Level.INFO else Level.WARNING)
    handler.setFormatter(
      new SimpleFormatter():
        override def format(record: java.util.logging.LogRecord): String =
          if verbose then s"[${record.getLevel}] ${record.getMessage}\n"
          else s"${record.getMessage}\n"
    )

    rootLogger.addHandler(handler)

/** Core application logic for the standalone Metals client.
  */
class MetalsLight(projectPath: Path, verbose: Boolean):
  private val logger = Logger.getLogger(classOf[MetalsLight].getName)

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private var launcher: Option[MetalsLauncherK]  = None
  private var lspClient: Option[LspClient]       = None
  private var metalsClient: Option[MetalsClient] = None

  def run(): Int =
    try
      logger.info("🚀 Starting Metals standalone MCP client...")

      val metalsLauncher = new MetalsLauncherK(projectPath)
      launcher = Some(metalsLauncher)

      import kyo.Sync
      import kyo.AllowUnsafe.embrace.danger
      if !Sync.Unsafe.evalOrThrow(metalsLauncher.validateProject()) then
        logger.severe("❌ Project validation failed")
        return 1

      logger.info("📦 Launching Metals language server...")
      val process = Sync.Unsafe.evalOrThrow(metalsLauncher.launchMetals()) match
        case Some(p) => new KyoProcessAdapter(p)
        case None    =>
          logger.severe("❌ Failed to launch Metals")
          return 1

      val client = new LspClient(process)
      lspClient = Some(client)

      import scala.concurrent.duration.*
      import scala.util.Try
      Try:
        scala.concurrent.Await.result(
          client.start().flatMap { _ =>
            logger.info("🔗 Connected to Metals LSP server")

            val metals = new MetalsClient(projectPath, client)
            metalsClient = Some(metals)

            metals.initialize().flatMap { success =>
              if success then
                logger.info("✅ Metals language server initialized")

                val monitor = new McpMonitor(projectPath)

                logger.info("⏳ Waiting for MCP server to start...")
                monitor.waitForMcpServer().flatMap {
                  case Some(mcpUrl) =>
                    monitor.printConnectionInfo(mcpUrl)
                    monitor.monitorMcpHealth(mcpUrl)
                  case None         =>
                    logger.severe("❌ MCP server failed to start")
                    Future.successful(false)
                }
              else
                logger.severe("❌ Failed to initialize Metals")
                Future.successful(false)
            }
          },
          Duration.Inf
        )
        0
      .recover {
        case _: InterruptedException =>
          logger.info("\n🛑 Interrupted by user")
          0
        case e: Exception            =>
          logger.severe(s"❌ Application failed: ${e.getMessage}")
          if verbose then e.printStackTrace()
          1
      }.get

    catch
      case _: InterruptedException =>
        logger.info("\n🛑 Interrupted by user")
        0
      case e: Exception            =>
        logger.severe(s"❌ Unexpected error: ${e.getMessage}")
        if verbose then e.printStackTrace()
        1

  def shutdown(): Unit =
    logger.info("🔄 Shutting down components...")

    // Shutdown in reverse order
    metalsClient.foreach { client =>
      try
        client.shutdown()
        logger.info("✅ Metals client shutdown")
      catch
        case e: Exception =>
          logger.warning(s"Error shutting down Metals client: ${e.getMessage}")
    }

    lspClient.foreach { client =>
      try
        client.shutdown()
        logger.info("✅ LSP client shutdown")
      catch
        case e: Exception =>
          logger.warning(s"Error shutting down LSP client: ${e.getMessage}")
    }

    launcher.foreach { launcher =>
      try
        import kyo.Sync
        import kyo.AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(launcher.shutdown())
        logger.info("✅ Metals process shutdown")
      catch
        case e: Exception =>
          logger.warning(s"Error shutting down Metals launcher: ${e.getMessage}")
    }

    logger.info("👋 Goodbye!")
