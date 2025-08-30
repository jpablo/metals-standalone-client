package scala.meta.metals.standalone

import kyo.*
import kyo.Process as KyoProcess
import kyo.Log

import java.nio.file.{Files, Path, Paths}
import scala.util.Try

/** Kyo-based port of MetalsLauncher.
  *
  * Uses kyo.Process for spawning external commands and wraps filesystem and environment
  * access in Sync effects. API mirrors the original MetalsLauncher where practical, but
  * returns Kyo effects instead of performing side effects eagerly.
  */
class MetalsLauncherK(projectPath: Path):

  /** Track the spawned Metals process (Kyo wrapper) for shutdown. */
  @volatile private var metalsProcess: Option[KyoProcess] = None

  enum MetalsInstallation:
    case CoursierInstallation(javaExecutable: String, classpath: String)
    case SbtDevelopment(sbtExecutable: String, repoDir: Path)
    case JarInstallation(javaExecutable: String, jarPath: String)
    case DirectCommand(executable: String)

  /** Try discovery methods in order. */
  def findMetalsInstallation()(using Frame): Option[MetalsInstallation] < Sync =
    for
      cs   <- findCoursierInstallation()
      sbt  <- findSbtDevelopment()
      jar  <- findJarInstallation()
      exec <- findDirectCommand()
    yield cs.orElse(sbt).orElse(jar).orElse(exec)

  private def findCoursierInstallation()(using Frame): Option[MetalsInstallation] < Sync =
    for
      maybeCs <- findExecutable("cs").map(_.orElse(None))
      maybeCr <- maybeCs match
        case s @ Some(_) => Sync.defer(s)
        case None        => findExecutable("coursier")
      res <- maybeCr match
        case Some(cs) =>
          Log.info("Attempting to fetch Metals classpath via Coursier...")
            .andThen {
              val cmd = KyoProcess.Command(cs, "fetch", "--classpath", "org.scalameta:metals_2.13:1.6.0")
              cmd.text.map(_.trim).flatMap { cp =>
                if cp.nonEmpty then
                  findJavaExecutable().map(_.map(j => MetalsInstallation.CoursierInstallation(j, cp)))
                else
                  Log.warn("Empty classpath returned from coursier").andThen(Sync.defer(None))
              }
            }
        case None     => Sync.defer(None)
    yield res

  private def findSbtDevelopment()(using Frame): Option[MetalsInstallation] < Sync =
    Sync.defer {
      val currentDir = Paths.get(".").toAbsolutePath.normalize()
      val buildSbt   = currentDir.resolve("build.sbt")
      if Files.exists(buildSbt) then Some(currentDir) else None
    }.flatMap {
      case Some(dir) =>
        findExecutable("sbt").map(_.map(sbt => MetalsInstallation.SbtDevelopment(sbt, dir)))
      case None      => Sync.defer(None)
    }

  private def findJarInstallation()(using Frame): Option[MetalsInstallation] < Sync =
    Sync.defer {
      val currentDir   = Paths.get(".").toAbsolutePath.normalize()
      val metalsTarget = currentDir.resolve("metals/target")
      if Files.exists(metalsTarget) then Some(metalsTarget) else None
    }.flatMap {
      case Some(targetDir) =>
        Sync.defer {
          Try {
            import scala.jdk.OptionConverters.*
            Files
              .walk(targetDir)
              .filter(p => p.toString.endsWith(".jar") && p.toString.contains("metals"))
              .findFirst()
              .toScala
              .map(_.toString)
          }.toOption.flatten
        }.flatMap {
          case Some(jarPath) => findJavaExecutable().map(_.map(j => MetalsInstallation.JarInstallation(j, jarPath)))
          case None          => Sync.defer(None)
        }
      case None           => Sync.defer(None)
    }

  private def findDirectCommand()(using Frame): Option[MetalsInstallation] < Sync =
    findExecutable("metals").map(_.map(MetalsInstallation.DirectCommand.apply))

  private def findExecutable(name: String)(using Frame): Option[String] < Sync =
    // Search PATH for an executable matching `name`.
    Sync.defer(sys.env.getOrElse("PATH", "").split(java.io.File.pathSeparator).toList).map { paths =>
      paths
        .map(Paths.get(_).resolve(name))
        .find(p => Files.exists(p) && Files.isRegularFile(p) && Files.isExecutable(p))
        .map(_.toString)
    }

  private def findJavaExecutable()(using Frame): Option[String] < Sync =
    // Try JAVA_HOME/bin/java first, then PATH
    Sync.defer(sys.env.get("JAVA_HOME")).flatMap {
      case Some(javaHome) =>
        val p = Paths.get(javaHome, "bin", "java")
        Sync.defer(if Files.exists(p) then Some(p.toString) else None)
      case None           => Sync.defer(None)
    }.flatMap {
      case s @ Some(_) => Sync.defer(s)
      case None        => findExecutable("java")
    }

  def launchMetals()(using Frame): Option[KyoProcess] < Sync =
    Log.info("Looking for Metals installation...")
      .andThen(findMetalsInstallation())
      .flatMap {
        case Some(installation) =>
          val command = buildCommand(installation)
          val workDir = getWorkingDirectory(installation)

          Log.info(s"Starting Metals: ${command.mkString(" ")}")
            .andThen {
              val cmd = KyoProcess.Command(command*)
                .cwd(workDir)
                .redirectErrorStream(false)

              cmd.spawn.flatMap { p =>
                metalsProcess = Some(p)
                Log.info("Metals process started").andThen(Sync.defer(Some(p)))
              }
            }
        case None =>
          Log.error("Could not find Metals installation").andThen(Sync.defer(None))
      }

  private def buildCommand(installation: MetalsInstallation): Seq[String] =
    installation match
      case MetalsInstallation.CoursierInstallation(java, classpath) =>
        Seq(java, "-cp", classpath, "scala.meta.metals.Main")
      case MetalsInstallation.SbtDevelopment(sbt, _)                =>
        Seq(sbt, "metals/run")
      case MetalsInstallation.JarInstallation(java, jarPath)        =>
        Seq(java, "-jar", jarPath)
      case MetalsInstallation.DirectCommand(executable)             =>
        Seq(executable)

  private def getWorkingDirectory(installation: MetalsInstallation): Path =
    installation match
      case MetalsInstallation.SbtDevelopment(_, repoDir) => repoDir
      case _                                             => projectPath

  def isScalaProject()(using Frame): Boolean < Sync =
    val indicators = List(
      "build.sbt",
      "Build.scala",
      "build.sc",     // Mill
      "pom.xml",      // Maven with Scala
      "build.gradle", // Gradle with Scala
      "project.scala" // Scala CLI
    )

    def hasIndicator: Boolean =
      indicators.exists { name =>
        val p = projectPath.resolve(name)
        Files.exists(p)
      }

    Sync.defer {
      if hasIndicator then true
      else
        val scalaExts = List(".scala", ".sc")
        Try {
          Files.walk(projectPath).anyMatch(p => scalaExts.exists(ext => p.toString.endsWith(ext)))
        }.getOrElse(false)
    }

  def validateProject()(using Frame): Boolean < Sync =
    Sync.defer {
      if !Files.exists(projectPath) then
        false
      else if !Files.isDirectory(projectPath) then
        false
      else true
    }.flatMap { ok =>
      if !ok then
        val msg =
          if !Files.exists(projectPath) then s"Project path does not exist: $projectPath"
          else s"Project path is not a directory: $projectPath"
        Log.error(msg).andThen(Sync.defer(false))
      else
        isScalaProject().map { scalaLike =>
          if !scalaLike then
            Log.warn("Directory does not appear to be a Scala project")
          true
        }
    }

  def shutdown()(using Frame): Unit < Sync =
    metalsProcess match
      case Some(p) =>
        for
          _ <- Log.info("Shutting down Metals process...")
          _ <- p.destroy
          terminated <- p.isAlive.map(alive => !alive)
          _ <-
            if !terminated then
              Log.warn("Metals process did not terminate gracefully, force killing...")
                .andThen(p.destroyForcibly.map(_ => ()))
            else Sync.defer(())
          _ <- Log.info("Metals process terminated")
        yield metalsProcess = None
      case None => Sync.defer(())
