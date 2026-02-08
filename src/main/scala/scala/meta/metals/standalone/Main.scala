package scala.meta.metals.standalone

import java.nio.file.{Path, Paths}
import java.util.logging.{ConsoleHandler, Level, Logger, SimpleFormatter}
import scala.concurrent.{ExecutionContext, Future}

/** Main entry point for the standalone Metals MCP client.
  *
  * This application launches Metals language server in minimal mode and enables the MCP server for AI assistant
  * integration without requiring a full IDE client like VS Code or Cursor.
  */
object Main:
  private val logger = Logger.getLogger(Main.getClass.getName)

  sealed trait ParseResult
  case class Parsed(config: Config) extends ParseResult
  case object HelpRequested extends ParseResult
  case class InvalidArgs(args: List[String]) extends ParseResult

  case class Config(
      projectPath: Path = Paths.get(".").toAbsolutePath.normalize(),
      verbose: Boolean = false,
      metalsArgs: Seq[String] = Seq.empty
  )

  def main(args: Array[String]): Unit =
    parseArgs(args) match
      case Parsed(config) =>
        setupLogging(config.verbose)

        val app = new MetalsLight(config.projectPath, config.verbose, config.metalsArgs)

        sys.addShutdownHook {
          logger.info("\nShutting down...")
          app.shutdown()
        }

        val exitCode = app.run()
        sys.exit(exitCode)
      case HelpRequested =>
        setupLogging(true)
        printUsage()
        sys.exit(0)
      case InvalidArgs(invalid) =>
        setupLogging(true)
        logger.severe(s"Error: Invalid arguments: ${invalid.mkString(" ")}")
        printUsage()
        sys.exit(1)

  def parseArgs(args: Array[String]): ParseResult =
    val (clientArgs, rest) = args.toList.span(_ != "--")
    val metalsArgs         = rest.drop(1) // drop the "--" separator itself

    clientArgs match
      case Nil                              => Parsed(Config(metalsArgs = metalsArgs))
      case "--help" :: _ | "-h" :: _        => HelpRequested
      case "--verbose" :: Nil | "-v" :: Nil =>
        Parsed(Config(verbose = true, metalsArgs = metalsArgs))
      case "--verbose" :: path :: Nil       =>
        Parsed(Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, metalsArgs = metalsArgs))
      case "-v" :: path :: Nil              =>
        Parsed(Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, metalsArgs = metalsArgs))
      case path :: "--verbose" :: Nil       =>
        Parsed(Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, metalsArgs = metalsArgs))
      case path :: "-v" :: Nil              =>
        Parsed(Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, metalsArgs = metalsArgs))
      case path :: Nil                      =>
        Parsed(Config(Paths.get(path).toAbsolutePath.normalize(), metalsArgs = metalsArgs))
      case invalid                          =>
        InvalidArgs(invalid)

  private def printUsage(): Unit =
    logger.info("""
                  |Metals Standalone MCP Client
                  |
                  |Usage: metals-standalone-client [OPTIONS] [PROJECT_PATH] [-- METALS_ARGS...]
                  |   or: sbt run [OPTIONS] [PROJECT_PATH] [-- METALS_ARGS...]
                  |
                  |Arguments:
                  |  PROJECT_PATH    Path to Scala project directory (default: current directory)
                  |  METALS_ARGS     Extra arguments passed directly to the Metals process
                  |
                  |Options:
                  |  -v, --verbose   Enable verbose logging
                  |  -h, --help      Show this help message
                  |
                  |Examples:
                  |  metals-standalone-client                    # Use current directory
                  |  metals-standalone-client /path/to/project   # Use specific project path
                  |  metals-standalone-client --verbose .        # Enable verbose logging
                  |  metals-standalone-client -- -Dmetals.verbose  # Pass JVM flags to Metals
                  |  metals-standalone-client /path -- -Xmx4g   # Project path + Metals args
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
class MetalsLight(projectPath: Path, verbose: Boolean, metalsArgs: Seq[String] = Seq.empty):
  private val logger = Logger.getLogger(classOf[MetalsLight].getName)

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private var launcher: Option[MetalsLauncher]   = None
  private var lspClient: Option[LspClient]       = None
  private var metalsClient: Option[MetalsClient] = None
  private var monitor: Option[McpMonitor]        = None

  def run(): Int =
    val spinner = new Spinner
    try
      logger.info("🚀 Starting Metals standalone MCP client...")

      val metalsLauncher = new MetalsLauncher(projectPath, metalsArgs)
      launcher = Some(metalsLauncher)

      if !metalsLauncher.validateProject() then
        logger.severe("❌ Project validation failed")
        return 1

      logger.info("📦 Launching Metals language server...")
      if !verbose then spinner.start("Starting Metals...")
      val process = metalsLauncher.launchMetals() match
        case Some(p) => p
        case None    =>
          spinner.stop()
          logger.severe("❌ Failed to launch Metals")
          return 1

      val client = new LspClient(process)
      lspClient = Some(client)

      import scala.concurrent.duration.*
      import scala.util.Try
      Try:
        val success = scala.concurrent.Await.result(
          client.start().flatMap { _ =>
            logger.info("🔗 Connected to Metals LSP server")

            val metals = new MetalsClient(projectPath, client)
            metalsClient = Some(metals)

            spinner.update("Initializing...")
            metals.initialize().flatMap { success =>
              if success then
                val versionSuffix = metals.serverVersion.map(v => s" (v$v)").getOrElse("")
                logger.info(s"✅ Metals language server initialized$versionSuffix")

                val monitorInstance = new McpMonitor(projectPath)
                monitor = Some(monitorInstance)

                logger.info("⏳ Waiting for MCP server to start...")
                spinner.update("Waiting for MCP server...")
                monitorInstance.waitForMcpServer().flatMap {
                  case Some(mcpUrl) =>
                    spinner.stop()
                    monitorInstance.printConnectionInfo(mcpUrl, metals.serverVersion)
                    monitorInstance.monitorMcpHealth(mcpUrl)
                  case None         =>
                    spinner.stop()
                    logger.severe("❌ MCP server failed to start")
                    Future.successful(false)
                }
              else
                spinner.stop()
                logger.severe("❌ Failed to initialize Metals")
                Future.successful(false)
            }
          },
          Duration.Inf
        )
        if success then 0 else 1
      .recover {
        case _: InterruptedException =>
          spinner.stop()
          logger.info("\n🛑 Interrupted by user")
          0
        case e: Exception            =>
          spinner.stop()
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
        launcher.shutdown()
        logger.info("✅ Metals process shutdown")
      catch
        case e: Exception =>
          logger.warning(s"Error shutting down Metals launcher: ${e.getMessage}")
    }

    monitor.foreach { monitor =>
      try
        monitor.shutdown()
        logger.info("✅ MCP monitor shutdown")
      catch
        case e: Exception =>
          logger.warning(s"Error shutting down MCP monitor: ${e.getMessage}")
    }

    logger.info("👋 Goodbye!")
