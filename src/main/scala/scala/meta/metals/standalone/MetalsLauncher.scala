package scala.meta.metals.standalone

import java.nio.file.Path

object MetalsLauncher:
  enum MetalsInstallation:
    case CoursierInstallation(javaExecutable: String, classpath: String)
    case SbtDevelopment(sbtExecutable: String, repoDir: Path)
    case JarInstallation(javaExecutable: String, jarPath: String)
    case DirectCommand(executable: String)

  def buildCommand(installation: MetalsInstallation): Seq[String] =
    installation match
      case MetalsInstallation.CoursierInstallation(java, classpath) =>
        Seq(java, "-cp", classpath, "scala.meta.metals.Main")

      case MetalsInstallation.SbtDevelopment(sbt, _) =>
        Seq(sbt, "metals/run")

      case MetalsInstallation.JarInstallation(java, jarPath) =>
        Seq(java, "-jar", jarPath)

      case MetalsInstallation.DirectCommand(executable) =>
        Seq(executable)

  def getWorkingDirectory(installation: MetalsInstallation, projectPath: Path): Path =
    installation match
      case MetalsInstallation.SbtDevelopment(_, repoDir) => repoDir
      case _                                             => projectPath
