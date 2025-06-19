ThisBuild / organization := "org.scalameta"
ThisBuild / version := "1.6.1-SNAPSHOT"
ThisBuild / scalaVersion := "3.7.1"

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val V = new {
  val scala3 = "3.7.1"
  val munit = "1.1.1"
}

lazy val root = project
  .in(file("."))
  .settings(
    name := "metals-standalone-client",
    description := "Standalone Metals MCP client for AI code assistants",

    // Main class configuration
    Compile / run / fork := true,
    Compile / mainClass := Some("scala.meta.metals.standalone.Main"),

    // Dependencies
    libraryDependencies ++= List(
      // JSON processing
      "io.circe" %% "circe-core" % "0.14.14",
      "io.circe" %% "circe-parser" % "0.14.14",

      // CLI argument parsing
      "com.github.scopt" %% "scopt" % "4.1.0",

      // HTTP client for health checks
      "com.softwaremill.sttp.client3" %% "core" % "3.11.0",
      "com.softwaremill.sttp.client3" %% "circe" % "3.11.0",

      // Test dependencies
      "org.scalameta" %% "munit" % V.munit % Test
    ),

    // Compiler options
    scalacOptions ++= List(
      "-deprecation",
      "-unchecked",
      "-feature",
      "-Wunused:imports",
    ),

    // Java compatibility
    javacOptions ++= List(
      "-Xlint:all",
      "-Werror"
    ),

    // Resolvers
    resolvers ++= Resolver.sonatypeOssRepos("public"),
    resolvers ++= Resolver.sonatypeOssRepos("snapshot"),

    // Test framework
    testFrameworks := List(new TestFramework("munit.Framework")),

    // Assembly plugin configuration for creating fat jar
    assembly / assemblyJarName := "metals-standalone-client.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case x => MergeStrategy.first
    }

  )
  .enablePlugins(AssemblyPlugin)
