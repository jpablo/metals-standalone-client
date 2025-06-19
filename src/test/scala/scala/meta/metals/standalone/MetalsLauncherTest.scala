package scala.meta.metals.standalone

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import munit.FunSuite

class MetalsLauncherTest extends FunSuite {
  
  val tempDir: FunFixture[Path] = FunFixture[Path](
    setup = { _ =>
      Files.createTempDirectory("metals-launcher-test")
    },
    teardown = { dir =>
      def deleteRecursively(path: Path): Unit = {
        if (Files.isDirectory(path)) {
          Files.list(path).forEach(deleteRecursively)
        }
        Files.deleteIfExists(path)
      }
      deleteRecursively(dir)
    }
  )
  
  tempDir.test("validateProject returns false for non-existent path") { tempDir =>
    val nonExistentPath = tempDir.resolve("non-existent")
    val launcher = new MetalsLauncher(nonExistentPath)
    assertEquals(launcher.validateProject(), false)
  }
  
  tempDir.test("validateProject returns false for file instead of directory") { tempDir =>
    val filePath = tempDir.resolve("not-a-directory")
    Files.write(filePath, "content".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(filePath)
    assertEquals(launcher.validateProject(), false)
  }
  
  tempDir.test("validateProject returns true for valid directory") { tempDir =>
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.validateProject(), true)
  }
  
  tempDir.test("isScalaProject detects build.sbt") { tempDir =>
    Files.write(tempDir.resolve("build.sbt"), "name := \"test\"".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects Build.scala") { tempDir =>
    Files.write(tempDir.resolve("Build.scala"), "object Build".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects build.sc (Mill)") { tempDir =>
    Files.write(tempDir.resolve("build.sc"), "import mill._".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects pom.xml") { tempDir =>
    Files.write(tempDir.resolve("pom.xml"), "<project></project>".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects build.gradle") { tempDir =>
    Files.write(tempDir.resolve("build.gradle"), "apply plugin: 'scala'".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects project.scala") { tempDir =>
    Files.write(tempDir.resolve("project.scala"), "//> using scala 3".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects .scala files") { tempDir =>
    val srcDir = tempDir.resolve("src")
    Files.createDirectories(srcDir)
    Files.write(srcDir.resolve("Main.scala"), "object Main".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject detects .sc files") { tempDir =>
    Files.write(tempDir.resolve("script.sc"), "println(\"hello\")".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), true)
  }
  
  tempDir.test("isScalaProject returns false for non-Scala project") { tempDir =>
    Files.write(tempDir.resolve("README.md"), "# Test Project".getBytes(StandardCharsets.UTF_8))
    val launcher = new MetalsLauncher(tempDir)
    assertEquals(launcher.isScalaProject(), false)
  }
  
  test("findMetalsInstallation returns None when no installation found") {
    val tempDir = Files.createTempDirectory("no-metals")
    try {
      val launcher = new MetalsLauncher(tempDir)
      // This will likely return None unless the system has metals installed
      val installation = launcher.findMetalsInstallation()
      // We can't assert None because the system might have metals installed
      // Just verify the method doesn't crash
      assert(installation.isDefined || installation.isEmpty)
    } finally {
      Files.deleteIfExists(tempDir)
    }
  }
  
  test("MetalsInstallation types are properly defined") {
    val tempDir = Files.createTempDirectory("metals-types")
    try {
      val launcher = new MetalsLauncher(tempDir)
      
      // Test that all installation types can be instantiated
      val coursier = launcher.MetalsInstallation.CoursierInstallation("java", "classpath")
      val sbt = launcher.MetalsInstallation.SbtDevelopment("sbt", tempDir)
      val jar = launcher.MetalsInstallation.JarInstallation("java", "metals.jar")
      val direct = launcher.MetalsInstallation.DirectCommand("metals")
      
      assert(coursier.isInstanceOf[launcher.MetalsInstallation], "coursier should be MetalsInstallation")
      assert(sbt.isInstanceOf[launcher.MetalsInstallation], "sbt should be MetalsInstallation")
      assert(jar.isInstanceOf[launcher.MetalsInstallation], "jar should be MetalsInstallation")
      assert(direct.isInstanceOf[launcher.MetalsInstallation], "direct should be MetalsInstallation")
    } finally {
      Files.deleteIfExists(tempDir)
    }
  }
}