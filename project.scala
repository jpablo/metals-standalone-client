//> using scala "3.7.1"

// Metals version configuration
//> using resourceDir "src/main/resources"

// JSON processing dependencies
//> using dep "io.circe::circe-core:0.14.14"
//> using dep "io.circe::circe-parser:0.14.14"

// CLI argument parsing
//> using dep "com.github.scopt::scopt:4.1.0"

// HTTP client for health checks
//> using dep "com.softwaremill.sttp.client3::core:3.11.0"
//> using dep "com.softwaremill.sttp.client3::circe:3.11.0"

// Test dependencies
//> using test.dep "org.scalameta::munit:1.1.1"

// Compiler options
//> using option "-deprecation"
//> using option "-unchecked" 
//> using option "-feature"
//> using option "-Wunused:imports"

// Java options (removed -Xlint:all as it's for javac, not java runtime)

// Repositories
//> using repository "sonatype:public"
//> using repository "sonatype:snapshots"

// Main class configuration
//> using mainClass "scala.meta.metals.standalone.Main"