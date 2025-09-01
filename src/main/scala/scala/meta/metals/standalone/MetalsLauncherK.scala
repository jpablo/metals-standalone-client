package scala.meta.metals.standalone

import kyo.*

import java.lang
import java.nio.file.{Files, Path, Paths}
import java.util.logging.Logger
import scala.jdk.OptionConverters.*
import scala.meta.metals.standalone.MetalsLauncher.MetalsInstallation

/** Kyo-based launcher for the Metals language server process.
  *
  * Supports multiple discovery methods:
  *   - Coursier installation/discovery
  *   - Development SBT execution (when in metals repo)
  *   - Local JAR files
  *   - Direct command
  */
class MetalsLauncherK(projectPath: Path):
  private val logger = Logger.getLogger(classOf[MetalsLauncher].getName)
  private var metalsProcess: Option[java.lang.Process] = None

  def findMetalsInstallation(): Option[MetalsInstallation] < Sync =
    for
      _ <- Log.info("Looking for Metals installation...")
      coursier <- findCoursierInstallation()
      sbt <- if coursier.isEmpty then findSbtDevelopment() else Sync.defer(None)
      jar <- if coursier.isEmpty && sbt.isEmpty then findJarInstallation() else Sync.defer(None)
      direct <- if coursier.isEmpty && sbt.isEmpty && jar.isEmpty then findDirectCommand() else Sync.defer(None)
    yield coursier.orElse(sbt).orElse(jar).orElse(direct)


  private def findCoursierInstallation(): Option[MetalsInstallation] < Sync =
    for
      coursierCommand <- findExecutable("cs").flatMap { cs =>
        if cs.isEmpty then findExecutable("coursier") else Sync.defer(cs)
      }
      result <- coursierCommand match
        case None => Sync.defer(None)
        case Some(cs) =>
          for
            _ <- Log.debug(s"Attempting to fetch Metals classpath via Coursier...")
            classpath <- Abort.run {
              Process.Command(cs, "fetch", "--classpath", "org.scalameta:metals_2.13:1.6.0")
                .text
                .map(_.trim)
            }.map(_.getOrElse(""))
            java <- if classpath.nonEmpty then findJavaExecutable() else Sync.defer(None)
          yield java.map(j => MetalsInstallation.CoursierInstallation(j, classpath))
    yield result

  private def findSbtDevelopment(): Option[MetalsInstallation] < Sync =
    for
      currentDir <- Sync.defer(Paths.get(".").toAbsolutePath.normalize())
      buildSbt <- Sync.defer(currentDir.resolve("build.sbt"))
      result <- if Files.exists(buildSbt) then
        findExecutable("sbt").flatMap { sbtOpt =>
          sbtOpt match
            case Some(sbt) =>
              Log.debug("Using SBT to run Metals from source (development version)").map { _ =>
                Some(MetalsInstallation.SbtDevelopment(sbt, currentDir))
              }
            case None => Sync.defer(None)
        }
      else Sync.defer(None)
    yield result

  private def findJarInstallation(): Option[MetalsInstallation] < Sync =
    for
      currentDir <- Sync.defer(Paths.get(".").toAbsolutePath.normalize())
      metalsTarget <- Sync.defer(currentDir.resolve("metals/target"))
      result <- if Files.exists(metalsTarget) then
        Sync.defer {
          val jarPath = Files
            .walk(metalsTarget)
            .filter(p => p.toString.endsWith(".jar") && p.toString.contains("metals"))
            .findFirst()
            .toScala
          jarPath
        }.flatMap {
          case Some(jar) =>
            findJavaExecutable().map { javaOpt =>
              javaOpt.map(java => MetalsInstallation.JarInstallation(java, jar.toString))
            }
          case None => Sync.defer(None)
        }
      else Sync.defer(None)
    yield result

  private def findDirectCommand(): Option[MetalsInstallation] < Sync =
    findExecutable("metals").map(_.map(MetalsInstallation.DirectCommand.apply))

  private def findExecutable(name: String): Option[String] < Sync =
    Abort.run {
      Process.Command("which", name)
        .text
        .map { result =>
          val trimmed = result.trim
          if trimmed.nonEmpty then Some(trimmed) else None
        }
    }.map(_.getOrElse(None))

  private def findJavaExecutable(): Option[String] < Sync =
    System.env[String]("JAVA_HOME").map { javaHome =>
      javaHome.map { home =>
        val javaPath = Paths.get(home, "bin", "java")
        if Files.exists(javaPath) then Some(javaPath.toString) else None
      }.getOrElse(None)
    }.flatMap { fromHome =>
      if fromHome.isDefined then Sync.defer(fromHome)
      else findExecutable("java")
    }

  def launchMetals(): lang.Process < (Sync & Abort[Throwable]) =
    for
      installation <- findMetalsInstallation().map {
        case Some(inst) => inst
        case None => throw new RuntimeException("Could not find Metals installation")
      }
      command = MetalsLauncher.buildCommand(installation)
      workDir = MetalsLauncher.getWorkingDirectory(installation, projectPath)
      _ <- Log.info(s"Starting Metals: ${command.head} ...")
      _ <- Log.debug(s"Working directory: $workDir")
      _ <- Log.debug("About to spawn Metals process...")
//      process <- createProcess(command, workDir)
      process <- createJavaProcess(command, workDir)
      _ = metalsProcess = Some(process)
      _ <- Log.debug("Metals process started")
    yield process

  // TODO: Kyo's Process is not working for some reason.
  private def createProcess(command: Seq[String], workDir: Path): Process < (Sync & Abort[Throwable]) =
    if command.isEmpty then
      Abort.fail(new RuntimeException("Empty command"))
    else
      Process.Command(command*)
        .cwd(workDir)
//        .stdin(Process.Input.Pipe)   // allow LSP client to write to Metals stdin
//        .stdout(Process.Output.Pipe) // read Metals stdout for LSP messages
//        .stderr(Process.Output.Pipe) // drain Metals stderr separately
        .spawn

  private def createJavaProcess(command: Seq[String], workDir: Path): lang.Process < Sync =
    import scala.jdk.CollectionConverters.*

    val processBuilder = new java.lang.ProcessBuilder(command.asJava)
      .directory(workDir.toFile)
      .redirectErrorStream(false)

    Sync.defer(processBuilder.start())


  def isScalaProject(): Boolean < Sync =
    val scalaIndicators = Seq(
      "build.sbt",
      "Build.scala",
      "build.sc",     // Mill
      "pom.xml",      // Maven with Scala
      "build.gradle", // Gradle with Scala
      "project.scala" // Scala CLI
    )

    def checkIndicators(): Boolean < Sync =
      scalaIndicators.foldLeft(Sync.defer(false)) { (acc, indicator) =>
        acc.flatMap { found =>
          if found then Sync.defer(true)
          else
            val path = projectPath.resolve(indicator)
            if Files.exists(path) then
              Log.debug(s"Detected Scala project via $indicator").map(_ => true)
            else Sync.defer(false)
        }
      }

    checkIndicators().flatMap { found =>
      if !found then
        // Check for Scala source files
        val scalaExtensions = Seq(".scala", ".sc")
        Sync.defer {
          Files
            .walk(projectPath)
            .anyMatch(p => scalaExtensions.exists(ext => p.toString.endsWith(ext)))
        }.flatMap { hasFiles =>
          if hasFiles then
            Log.debug("Detected Scala project via .scala files").map(_ => true)
          else
            Log.warn("No Scala project indicators found").map(_ => false)
        }
      else Sync.defer(true)
    }

  def validateProject(): Boolean < (Sync & Abort[Throwable]) =
    if !Files.exists(projectPath) then
      Abort.fail(new RuntimeException(s"Project path does not exist: $projectPath"))
    else if !Files.isDirectory(projectPath) then
      Abort.fail(new RuntimeException(s"Project path is not a directory: $projectPath"))
    else
      isScalaProject().flatMap { isScala =>
        if !isScala then
          Log.warn("Directory does not appear to be a Scala project").map(_ => true)
        else
          Sync.defer(true)
      }

  def shutdown(): Unit < Sync = {
    metalsProcess match {
      case Some(process) =>
        for
          _ <- Log.debug("Shutting down Metals process...")
          _ <- Sync.defer(process.destroy())
          _ <- if process.isAlive then
            for
              _ <- Log.warn("Metals process did not terminate gracefully, force killing...")
              _ <- Sync.defer(process.destroyForcibly())
            yield ()
          else Sync.defer(())
          _ <- Log.debug("Metals process terminated")
        yield ()
      case None =>
    }
  }


object MainLauncher extends KyoApp:
  val path = Paths.get("/Users/jpablo/proyectos/playground/graph-explorer").toAbsolutePath.normalize()
  val launcher = MetalsLauncherK(path)
  run {
    for
      valid <- launcher.validateProject()
      metals <- launcher.findMetalsInstallation()
      _ = pprint.log(metals)
      _ = println("--------------------------")
      process <- launcher.launchMetals()
    yield
      process
  }
