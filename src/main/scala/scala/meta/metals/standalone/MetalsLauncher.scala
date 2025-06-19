package scala.meta.metals.standalone

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try
import java.util.logging.Logger

/**
 * Launches and manages the Metals language server process.
 * 
 * Supports multiple discovery methods:
 * - Coursier installation/discovery
 * - Development SBT execution (when in metals repo)
 * - Local JAR files
 */
class MetalsLauncher(projectPath: Path) {
  private val logger = Logger.getLogger(classOf[MetalsLauncher].getName)
  private var metalsProcess: Option[java.lang.Process] = None
  
  sealed trait MetalsInstallation
  case class CoursierInstallation(javaExecutable: String, classpath: String) extends MetalsInstallation
  case class SbtDevelopment(sbtExecutable: String, repoDir: Path) extends MetalsInstallation
  case class JarInstallation(javaExecutable: String, jarPath: String) extends MetalsInstallation
  case class DirectCommand(executable: String) extends MetalsInstallation

  def findMetalsInstallation(): Option[MetalsInstallation] = {
    // Try to find coursier first
    findCoursierInstallation()
      .orElse(findSbtDevelopment())
      .orElse(findJarInstallation())
      .orElse(findDirectCommand())
  }

  private def findCoursierInstallation(): Option[MetalsInstallation] = {
    val coursierCommand = findExecutable("cs").orElse(findExecutable("coursier"))
    
    coursierCommand.flatMap { cs =>
      try {
        logger.info("Attempting to fetch Metals classpath via Coursier...")
        
        // Use coursier fetch command to get classpath
        val command = Seq(cs, "fetch", "--classpath", "org.scalameta:metals_2.13:1.6.0")
        val processBuilder = new java.lang.ProcessBuilder(command.asJava)
        val process = processBuilder.start()
        val result = scala.io.Source.fromInputStream(process.getInputStream).mkString.trim
        process.waitFor()
        
        if (result.nonEmpty) {
          findJavaExecutable().map(java => CoursierInstallation(java, result))
        } else {
          logger.warning("Empty classpath returned from coursier")
          None
        }
      } catch {
        case e: Exception =>
          logger.warning(s"Coursier fetch failed: ${e.getMessage}")
          None
      }
    }
  }

  private def findSbtDevelopment(): Option[MetalsInstallation] = {
    val currentDir = Paths.get(".").toAbsolutePath.normalize()
    val buildSbt = currentDir.resolve("build.sbt")
    
    if (Files.exists(buildSbt)) {
      findExecutable("sbt").map { sbt =>
        logger.info("Using SBT to run Metals from source (development version)")
        SbtDevelopment(sbt, currentDir)
      }
    } else None
  }

  private def findJarInstallation(): Option[MetalsInstallation] = {
    val currentDir = Paths.get(".").toAbsolutePath.normalize()
    val metalsTarget = currentDir.resolve("metals/target")
    
    if (Files.exists(metalsTarget)) {
      Try {
        Files.walk(metalsTarget)
          .filter(p => p.toString.endsWith(".jar") && p.toString.contains("metals"))
          .findFirst()
      }.toOption.flatMap(_.toScala).flatMap { jarPath =>
        findJavaExecutable().map(java => JarInstallation(java, jarPath.toString))
      }
    } else None
  }

  private def findDirectCommand(): Option[MetalsInstallation] = {
    findExecutable("metals").map(DirectCommand.apply)
  }

  private def findExecutable(name: String): Option[String] = {
    Try {
      val processBuilder = new java.lang.ProcessBuilder("which", name)
      val process = processBuilder.start()
      val result = scala.io.Source.fromInputStream(process.getInputStream).mkString.trim
      process.waitFor()
      if (result.nonEmpty) Some(result) else None
    }.getOrElse(None)
  }

  private def findJavaExecutable(): Option[String] = {
    // Check JAVA_HOME first
    sys.env.get("JAVA_HOME").flatMap { javaHome =>
      val javaPath = Paths.get(javaHome, "bin", "java")
      if (Files.exists(javaPath)) Some(javaPath.toString) else None
    }.orElse {
      // Check PATH
      findExecutable("java")
    }
  }

  def launchMetals(): Option[java.lang.Process] = {
    logger.info("Looking for Metals installation...")
    
    findMetalsInstallation() match {
      case Some(installation) =>
        val command = buildCommand(installation)
        val workDir = getWorkingDirectory(installation)
        
        logger.info(s"Starting Metals: ${command.mkString(" ")}")
        
        try {
          val processBuilder = new java.lang.ProcessBuilder(command.asJava)
            .directory(workDir.toFile)
            .redirectErrorStream(false)
          
          val process = processBuilder.start()
          
          metalsProcess = Some(process)
          logger.info(s"Metals process started")
          Some(process)
        } catch {
          case e: Exception =>
            logger.severe(s"Failed to start Metals: ${e.getMessage}")
            None
        }
        
      case None =>
        logger.severe("Could not find Metals installation")
        None
    }
  }

  private def buildCommand(installation: MetalsInstallation): Seq[String] = {
    installation match {
      case CoursierInstallation(java, classpath) =>
        Seq(java, "-cp", classpath, "scala.meta.metals.Main")
      case SbtDevelopment(sbt, _) =>
        Seq(sbt, "metals/run")
      case JarInstallation(java, jarPath) =>
        Seq(java, "-jar", jarPath)
      case DirectCommand(executable) =>
        Seq(executable)
    }
  }

  private def getWorkingDirectory(installation: MetalsInstallation): Path = {
    installation match {
      case SbtDevelopment(_, repoDir) => repoDir
      case _ => projectPath
    }
  }

  def isScalaProject(): Boolean = {
    val scalaIndicators = Seq(
      "build.sbt",
      "Build.scala", 
      "build.sc", // Mill
      "pom.xml",  // Maven with Scala
      "build.gradle", // Gradle with Scala
      "project.scala" // Scala CLI
    )
    
    val hasIndicator = scalaIndicators.exists { indicator =>
      val path = projectPath.resolve(indicator)
      if (Files.exists(path)) {
        logger.info(s"Detected Scala project via $indicator")
        true
      } else false
    }
    
    if (!hasIndicator) {
      // Check for Scala source files
      val scalaExtensions = Seq(".scala", ".sc")
      val hasScalaFiles = Try {
        Files.walk(projectPath)
          .anyMatch(p => scalaExtensions.exists(ext => p.toString.endsWith(ext)))
      }.getOrElse(false)
      
      if (hasScalaFiles) {
        logger.info("Detected Scala project via .scala files")
        true
      } else {
        logger.warning("No Scala project indicators found")
        false
      }
    } else hasIndicator
  }

  def validateProject(): Boolean = {
    if (!Files.exists(projectPath)) {
      logger.severe(s"Project path does not exist: $projectPath")
      false
    } else if (!Files.isDirectory(projectPath)) {
      logger.severe(s"Project path is not a directory: $projectPath")  
      false
    } else {
      if (!isScalaProject()) {
        logger.warning("Directory does not appear to be a Scala project")
        // Don't fail - Metals can work with any directory
      }
      true
    }
  }

  def shutdown(): Unit = {
    metalsProcess.foreach { process =>
      logger.info("Shutting down Metals process...")
      
      try {
        // Try graceful shutdown first
        process.destroy()
        
        val terminated = try {
          process.exitValue() // This will throw if process is still running
          true
        } catch {
          case _: IllegalThreadStateException => false
        }
        
        if (!terminated) {
          logger.warning("Metals process did not terminate gracefully, force killing...")
          process.destroyForcibly()
        }
        
        logger.info("Metals process terminated")
      } catch {
        case e: Exception =>
          logger.severe(s"Error shutting down Metals process: ${e.getMessage}")
      }
      
      metalsProcess = None
    }
  }
}

