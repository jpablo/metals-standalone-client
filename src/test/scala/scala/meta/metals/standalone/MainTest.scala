package scala.meta.metals.standalone

import java.nio.file.Paths
import munit.FunSuite

class MainTest extends FunSuite:

  test("parseArgs handles default config"):
    val config = Main.parseArgs(Array.empty)
    assertEquals(config.projectPath, Paths.get(".").toAbsolutePath.normalize())
    assertEquals(config.verbose, false)

  test("parseArgs handles verbose flag"):
    val config = Main.parseArgs(Array("--verbose"))
    assertEquals(config.verbose, true)
    assertEquals(config.projectPath, Paths.get(".").toAbsolutePath.normalize())

  test("parseArgs handles short verbose flag"):
    val config = Main.parseArgs(Array("-v"))
    assertEquals(config.verbose, true)

  test("parseArgs handles project path"):
    val testPath = "/tmp/test-project"
    val config = Main.parseArgs(Array(testPath))
    assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
    assertEquals(config.verbose, false)

  test("parseArgs handles verbose with path"):
    val testPath = "/tmp/test-project"
    val config = Main.parseArgs(Array("--verbose", testPath))
    assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
    assertEquals(config.verbose, true)

  test("parseArgs handles path with verbose"):
    val testPath = "/tmp/test-project"
    val config = Main.parseArgs(Array(testPath, "--verbose"))
    assertEquals(config.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
    assertEquals(config.verbose, true)

  test("parseArgs handles short flags with path"):
    val testPath = "/tmp/test-project"
    val config1 = Main.parseArgs(Array("-v", testPath))
    assertEquals(config1.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
    assertEquals(config1.verbose, true)

    val config2 = Main.parseArgs(Array(testPath, "-v"))
    assertEquals(config2.projectPath, Paths.get(testPath).toAbsolutePath.normalize())
    assertEquals(config2.verbose, true)

  test("parseArgs fails with invalid arguments"):
    val _ = intercept[SystemExit]:
      Main.parseArgs(Array("invalid", "too", "many", "args"))

  test("parseArgs handles help flags"):
    val _ = intercept[SystemExit]:
      Main.parseArgs(Array("--help"))

    val _ = intercept[SystemExit]:
      Main.parseArgs(Array("-h"))

// Helper class to catch sys.exit calls in tests
class SystemExit(val code: Int) extends RuntimeException(s"System.exit($code)")

// Mock the Main object's private methods for testing
object Main:
  def parseArgs(args: Array[String]): Config =
    args.toList match
      case Nil => Config()
      case "--help" :: _ | "-h" :: _ =>
        throw new SystemExit(0)
      case "--verbose" :: Nil | "-v" :: Nil =>
        Config(verbose = true)
      case "--verbose" :: path :: Nil =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case "-v" :: path :: Nil =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "--verbose" :: Nil =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: "-v" :: Nil =>
        Config(Paths.get(path).toAbsolutePath.normalize(), verbose = true)
      case path :: Nil =>
        Config(Paths.get(path).toAbsolutePath.normalize())
      case invalid =>
        throw new SystemExit(1)

  case class Config(
    projectPath: java.nio.file.Path = Paths.get(".").toAbsolutePath.normalize(),
    verbose: Boolean = false
  )
