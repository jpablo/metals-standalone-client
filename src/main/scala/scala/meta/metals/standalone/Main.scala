package scala.meta.metals.standalone

import java.nio.file.{Path, Paths}
 
import java.util.logging.{Logger as JULLogger, Level as JULLevel}

/** Main entry point for the standalone Metals MCP client.
  *
  * This application launches Metals language server in minimal mode and enables the MCP server for AI assistant
  * integration without requiring a full IDE client like VS Code or Cursor.
  */
import kyo.*

object Main extends KyoApp:

  case class Config(
      projectPath: Path = Paths.get(".").toAbsolutePath.normalize(),
      verbose: Boolean = false
  )

  run {
    val config = parseArgs(args.toList)
    val logName = "metals-standalone"
    // Configure Java Platform Logging (JUL) levels to match verbosity
    val juLevel = if config.verbose then JULLevel.FINE else JULLevel.INFO
    val root    = JULLogger.getLogger("")
    root.setLevel(juLevel)
    root.getHandlers.foreach(_.setLevel(juLevel))
    JULLogger.getLogger(logName).setLevel(juLevel)

    // Bridge Kyo Log and run the effect under a live logger
    val level = if config.verbose then Log.Level.debug else Log.Level.info
    Log.withConsoleLogger(logName, level) {
      new MetalsLight(config.projectPath).run()
    }
  }

  private def parseArgs(args: List[String]): Config =
    args match
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
        scala.Console.err.println(s"Error: Invalid arguments: ${invalid.mkString(" ")}")
        printUsage()
        sys.exit(1)

  private def printUsage(): Unit =
    println("""
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
