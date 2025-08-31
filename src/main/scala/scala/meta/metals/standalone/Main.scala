package scala.meta.metals.standalone

import java.nio.file.{Path, Paths}
 
import java.util.logging.{Logger as JULLogger, Level as JULLevel}
import scala.concurrent.duration.*

/** Main entry point for the standalone Metals MCP client.
  *
  * This application launches Metals language server in minimal mode and enables the MCP server for AI assistant
  * integration without requiring a full IDE client like VS Code or Cursor.
  */
import kyo.*

object Main extends KyoApp:

  case class Config(
      projectPath: Path = Paths.get(".").toAbsolutePath.normalize(),
      verbose: Boolean = false,
      initTimeout: FiniteDuration = 5.minutes
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
      new MetalsLight(config.projectPath, config.initTimeout).run()
    }
  }

  private def parseArgs(args: List[String]): Config =
    // allow override via env var METALS_INIT_TIMEOUT_SEC if flag not provided
    val defaultTimeout = scala.concurrent.duration.FiniteDuration(5, scala.concurrent.duration.MINUTES)
    val envTimeout = sys.env
      .get("METALS_INIT_TIMEOUT_SEC")
      .flatMap(s => scala.util.Try(s.toLong).toOption)
      .map(secs => scala.concurrent.duration.FiniteDuration(secs, scala.concurrent.duration.SECONDS))
    args match
      case Nil                              => Config(initTimeout = envTimeout.getOrElse(defaultTimeout))
      case "--help" :: _ | "-h" :: _        =>
        printUsage()
        sys.exit(0)
      case "--verbose" :: Nil | "-v" :: Nil =>
        Config(verbose = true, initTimeout = envTimeout.getOrElse(defaultTimeout))
      case "--init-timeout" :: value :: tail =>
        val base = parseArgs(tail)
        val secs = scala.util.Try(value.toLong).toOption
        val fd   = secs.map(v => scala.concurrent.duration.FiniteDuration(v, scala.concurrent.duration.SECONDS))
        base.copy(initTimeout = fd.orElse(envTimeout).getOrElse(base.initTimeout))
      case "--verbose" :: path :: Nil       =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, initTimeout = envTimeout.getOrElse(defaultTimeout))
      case "-v" :: path :: Nil              =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, initTimeout = envTimeout.getOrElse(defaultTimeout))
      case path :: "--verbose" :: Nil       =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, initTimeout = envTimeout.getOrElse(defaultTimeout))
      case path :: "-v" :: Nil              =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true, initTimeout = envTimeout.getOrElse(defaultTimeout))
      case path :: "--init-timeout" :: value :: Nil =>
        val secs = scala.util.Try(value.toLong).toOption
        val fd   = secs.map(v => scala.concurrent.duration.FiniteDuration(v, scala.concurrent.duration.SECONDS))
        Config(Paths.get(path).toAbsolutePath.normalize(), initTimeout = fd.orElse(envTimeout).getOrElse(defaultTimeout))
      case path :: Nil                      =>
        Config(Paths.get(path).toAbsolutePath.normalize(), initTimeout = envTimeout.getOrElse(defaultTimeout))
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
                  |  --init-timeout  Initialization timeout in seconds (default: 300). Alternatively set METALS_INIT_TIMEOUT_SEC.
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
