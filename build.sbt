ThisBuild / organization := "org.scalameta"
ThisBuild / version := "1.6.1-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.16"

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val V = new {
  val scala213 = "2.13.16"
  val coursier = "2.1.18"
  val munit = "1.0.3"
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
      "io.circe" %% "circe-core" % "0.14.10",
      "io.circe" %% "circe-parser" % "0.14.10",
      
      // CLI argument parsing  
      "com.github.scopt" %% "scopt" % "4.1.0",
      
      // Coursier for Metals discovery
      "io.get-coursier" %% "coursier" % V.coursier,
      
      // HTTP client for health checks
      "com.softwaremill.sttp.client3" %% "core" % "3.9.8",
      "com.softwaremill.sttp.client3" %% "circe" % "3.9.8",
      
      // Test dependencies
      "org.scalameta" %% "munit" % V.munit % Test
    ),
    
    // Compiler options
    scalacOptions ++= List(
      "-target:jvm-1.8",
      "-deprecation",
      "-unchecked",
      "-feature",
      "-Xlint",
      "-Xfatal-warnings",
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
    },
    
    // Publishing configuration
    licenses := Seq(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    homepage := Some(url("https://github.com/scalameta/metals")),
  )
  .enablePlugins(AssemblyPlugin)