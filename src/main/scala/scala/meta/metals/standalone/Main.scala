package scala.meta.metals.standalone

import java.nio.file.{Path, Paths}
import java.util.logging.{ConsoleHandler, Level, Logger, SimpleFormatter}

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
