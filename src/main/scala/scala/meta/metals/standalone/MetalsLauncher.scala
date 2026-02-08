package scala.meta.metals.standalone

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Try
import scala.util.Using
import java.util.logging.Logger

/** Launches and manages the Metals language server process.
  *
  * Supports multiple discovery methods:
  *   - Coursier installation/discovery
  *   - Development SBT execution (when in metals repo)
  *   - Local JAR files
  */
class MetalsLauncher(projectPath: Path, metalsArgs: Seq[String] = Seq.empty):
  private val logger = Logger.getLogger(classOf[MetalsLauncher].getName)

  private var metalsProcess: Option[java.lang.Process] = None
  private val metalsVersion                            = sys.env.getOrElse("METALS_VERSION", "1.+")

  enum MetalsInstallation:
    case CoursierInstallation(javaExecutable: String, classpath: String)
    case SbtDevelopment(sbtExecutable: String, repoDir: Path)
    case JarInstallation(javaExecutable: String, jarPath: String)
    case DirectCommand(executable: String)

  def findMetalsInstallation(): Option[MetalsInstallation] =
    findCoursierInstallation()
      .orElse(findSbtDevelopment())
      .orElse(findJarInstallation())
      .orElse(findDirectCommand())

  private def findCoursierInstallation(): Option[MetalsInstallation] =
    val coursierCommand = findExecutable("cs").orElse(findExecutable("coursier"))

    coursierCommand.flatMap { cs =>
      try
        logger.info(s"Attempting to fetch Metals classpath via Coursier (version $metalsVersion)...")

        // Use coursier fetch command to get classpath
        val command        = Seq(cs, "fetch", "--classpath", s"org.scalameta:metals_2.13:$metalsVersion")
        val processBuilder = new java.lang.ProcessBuilder(command.asJava)
        val process        = processBuilder.start()
        val result         = Using.resource(scala.io.Source.fromInputStream(process.getInputStream))(_.mkString.trim)
        process.waitFor()

        if result.nonEmpty then findJavaExecutable().map(java => MetalsInstallation.CoursierInstallation(java, result))
        else
          logger.warning("Empty classpath returned from coursier")
          None
      catch
        case e: Exception =>
          logger.warning(s"Coursier fetch failed: ${e.getMessage}")
          None
    }

  private def findSbtDevelopment(): Option[MetalsInstallation] =
    val currentDir = Paths.get(".").toAbsolutePath.normalize()
    val buildSbt   = currentDir.resolve("build.sbt")

    if Files.exists(buildSbt) then
      findExecutable("sbt").map { sbt =>
        logger.info("Using SBT to run Metals from source (development version)")
        MetalsInstallation.SbtDevelopment(sbt, currentDir)
      }
    else None

  private def findJarInstallation(): Option[MetalsInstallation] =
    val currentDir   = Paths.get(".").toAbsolutePath.normalize()
    val metalsTarget = currentDir.resolve("metals/target")

    if Files.exists(metalsTarget) then
      Try:
        Using.resource(Files.walk(metalsTarget)) { stream =>
          stream
            .filter(p => p.toString.endsWith(".jar") && p.toString.contains("metals"))
            .findFirst()
            .toScala
        }
      .toOption.flatten.flatMap: jarPath =>
        findJavaExecutable().map(java => MetalsInstallation.JarInstallation(java, jarPath.toString))
    else None

  private def findDirectCommand(): Option[MetalsInstallation] =
    findExecutable("metals").map(MetalsInstallation.DirectCommand.apply)

  private def findExecutable(name: String): Option[String] =
    Try:
      val processBuilder = new java.lang.ProcessBuilder("which", name)
      val process        = processBuilder.start()
      val result         = Using.resource(scala.io.Source.fromInputStream(process.getInputStream))(_.mkString.trim)
      process.waitFor()
      if result.nonEmpty then Some(result) else None
    .getOrElse(None)

  private def findJavaExecutable(): Option[String] =
    // Check JAVA_HOME first
    sys.env
      .get("JAVA_HOME")
      .flatMap: javaHome =>
        val javaPath = Paths.get(javaHome, "bin", "java")
        if Files.exists(javaPath) then Some(javaPath.toString) else None
      .orElse
    // Check PATH
    findExecutable("java")

  def launchMetals(): Option[java.lang.Process] =
    logger.info("Looking for Metals installation...")

    findMetalsInstallation() match
      case Some(installation) =>
        val command = buildCommand(installation)
        val workDir = getWorkingDirectory(installation)

        logger.info(s"Starting Metals: ${command.mkString(" ")}")

        try
          val processBuilder = new java.lang.ProcessBuilder(command.asJava)
            .directory(workDir.toFile)
            .redirectErrorStream(false)

          val process = processBuilder.start()

          metalsProcess = Some(process)
          logger.info(s"Metals process started")
          Some(process)
        catch
          case e: Exception =>
            logger.severe(s"Failed to start Metals: ${e.getMessage}")
            None

      case None =>
        logger.severe("Could not find Metals installation")
        None

  private def buildCommand(installation: MetalsInstallation): Seq[String] =
    installation match
      case MetalsInstallation.CoursierInstallation(java, classpath) =>
        Seq(java) ++ metalsArgs ++ Seq("-cp", classpath, "scala.meta.metals.Main")
      case MetalsInstallation.SbtDevelopment(sbt, _)                =>
        Seq(sbt) ++ metalsArgs ++ Seq("metals/run")
      case MetalsInstallation.JarInstallation(java, jarPath)        =>
        Seq(java) ++ metalsArgs ++ Seq("-jar", jarPath)
      case MetalsInstallation.DirectCommand(executable)             =>
        Seq(executable) ++ metalsArgs

  private def getWorkingDirectory(installation: MetalsInstallation): Path =
    installation match
      case MetalsInstallation.SbtDevelopment(_, repoDir) => repoDir
      case _                                             => projectPath

  def isScalaProject(): Boolean =
    val scalaIndicators = Seq(
      "build.sbt",
      "Build.scala",
      "build.sc",     // Mill
      "pom.xml",      // Maven with Scala
      "build.gradle", // Gradle with Scala
      "project.scala" // Scala CLI
    )

    val hasIndicator = scalaIndicators.exists { indicator =>
      val path = projectPath.resolve(indicator)
      if Files.exists(path) then
        logger.info(s"Detected Scala project via $indicator")
        true
      else false
    }

    if !hasIndicator then
      // Check for Scala source files
      val scalaExtensions = Seq(".scala", ".sc")
      val hasScalaFiles   =
        Try:
          Using.resource(Files.walk(projectPath)) { stream =>
            stream.anyMatch(p => scalaExtensions.exists(ext => p.toString.endsWith(ext)))
          }
        .getOrElse(false)

      if hasScalaFiles then
        logger.info("Detected Scala project via .scala files")
        true
      else
        logger.warning("No Scala project indicators found")
        false
    else hasIndicator

  def validateProject(): Boolean =
    if !Files.exists(projectPath) then
      logger.severe(s"Project path does not exist: $projectPath")
      false
    else if !Files.isDirectory(projectPath) then
      logger.severe(s"Project path is not a directory: $projectPath")
      false
    else
      if !isScalaProject() then
        logger.warning("Directory does not appear to be a Scala project")
        // Don't fail - Metals can work with any directory
      true

  def shutdown(): Unit =
    metalsProcess.foreach { process =>
      logger.info("Shutting down Metals process...")

      try
        // Try graceful shutdown first
        process.destroy()

        val terminated =
          try
            process.exitValue() // This will throw if process is still running
            true
          catch case _: IllegalThreadStateException => false

        if !terminated then
          logger.warning("Metals process did not terminate gracefully, force killing...")
          process.destroyForcibly()

        logger.info("Metals process terminated")
      catch
        case e: Exception =>
          logger.severe(s"Error shutting down Metals process: ${e.getMessage}")

      metalsProcess = None
    }
