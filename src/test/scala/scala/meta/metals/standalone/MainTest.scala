package scala.meta.metals.standalone

import java.nio.file.Paths
import munit.FunSuite

class MainTest extends FunSuite:

  test("parseArgs handles default config"):
    Main.parseArgs(Array.empty) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(".").toAbsolutePath.normalize())
        assertEquals(config.verbose, false)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles verbose flag"):
    Main.parseArgs(Array("--verbose")) match
      case Main.Parsed(config) =>
        assertEquals(config.verbose, true)
        assertEquals(config.projectPath, Paths.get(".").toAbsolutePath.normalize())
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles short verbose flag"):
    Main.parseArgs(Array("-v")) match
      case Main.Parsed(config) =>
        assertEquals(config.verbose, true)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles project path"):
    val testPath = "/tmp/test-project"
    Main.parseArgs(Array(testPath)) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.verbose, false)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles verbose with path"):
    val testPath = "/tmp/test-project"
    Main.parseArgs(Array("--verbose", testPath)) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.verbose, true)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles path with verbose"):
    val testPath = "/tmp/test-project"
    Main.parseArgs(Array(testPath, "--verbose")) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.verbose, true)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles short flags with path"):
    val testPath = "/tmp/test-project"
    Main.parseArgs(Array("-v", testPath)) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.verbose, true)
      case other =>
        fail(s"Unexpected parse result: $other")

    Main.parseArgs(Array(testPath, "-v")) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.verbose, true)
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs fails with invalid arguments"):
    Main.parseArgs(Array("invalid", "too", "many", "args")) match
      case Main.InvalidArgs(args) =>
        assertEquals(args, List("invalid", "too", "many", "args"))
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles help flags"):
    assertEquals(Main.parseArgs(Array("--help")), Main.HelpRequested)
    assertEquals(Main.parseArgs(Array("-h")), Main.HelpRequested)

  test("parseArgs handles metals args after -- separator"):
    Main.parseArgs(Array("--", "-Dfoo=bar")) match
      case Main.Parsed(config) =>
        assertEquals(config.metalsArgs, Seq("-Dfoo=bar"))
        assertEquals(config.verbose, false)
        assertEquals(config.projectPath, Paths.get(".").toAbsolutePath.normalize())
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles verbose with metals args"):
    Main.parseArgs(Array("-v", "--", "-Dfoo=bar")) match
      case Main.Parsed(config) =>
        assertEquals(config.verbose, true)
        assertEquals(config.metalsArgs, Seq("-Dfoo=bar"))
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs handles path with metals args"):
    val testPath = "/tmp/proj"
    Main.parseArgs(Array(testPath, "--", "--log-level", "INFO")) match
      case Main.Parsed(config) =>
        assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
        assertEquals(config.metalsArgs, Seq("--log-level", "INFO"))
      case other =>
        fail(s"Unexpected parse result: $other")

  test("parseArgs produces empty metalsArgs when no -- separator"):
    Main.parseArgs(Array("-v", "/tmp/proj")) match
      case Main.Parsed(config) =>
        assertEquals(config.metalsArgs, Seq.empty)
      case other =>
        fail(s"Unexpected parse result: $other")
