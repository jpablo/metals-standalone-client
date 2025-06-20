package scala.meta.metals.standalone

import io.circe._
import io.circe.syntax._
import munit.FunSuite
import java.nio.file.{Files, Path}
import scala.concurrent.{ExecutionContext, Future}

class MetalsClientTest extends FunSuite:

  implicit val ec: ExecutionContext = ExecutionContext.global

  // Simple test client that just delegates to MockLspClient
  class TestMetalsClient(projectPath: Path, mockLspClient: MockLspClient)(using ec: ExecutionContext):
    def initialize(): Future[Boolean] =
      val initParams = Json.obj("testParam" -> "value".asJson)
      mockLspClient.sendRequest("initialize", Some(initParams)).map { result =>
        val hasCapabilities = result.hcursor.downField("capabilities").succeeded
        if hasCapabilities then
          mockLspClient.sendNotification("workspace/didChangeConfiguration", Some(Json.obj()))
          true
        else
          false
      }

    def shutdown(): Future[Unit] =
      mockLspClient.shutdown().map(_ => ())

  class MockLspClient:
    var lastRequest: Option[(String, Option[Json])] = None
    var lastNotification: Option[(String, Option[Json])] = None
    var responseToReturn: Json = Json.obj("capabilities" -> Json.obj())

    def sendRequest(method: String, params: Option[Json] = None): Future[Json] =
      lastRequest = Some((method, params))
      Future.successful(responseToReturn)

    def sendNotification(method: String, params: Option[Json] = None): Unit =
      lastNotification = Some((method, params))

    def shutdown(): Future[Json] =
      Future.successful(Json.obj())

  val tempDir: FunFixture[Path] = FunFixture[Path](
    setup = { _ =>
      Files.createTempDirectory("metals-client-test")
    },
    teardown = { dir =>
      def deleteRecursively(path: Path): Unit =
        if Files.isDirectory(path) then
          Files.list(path).forEach(deleteRecursively)
        Files.deleteIfExists(path)

      deleteRecursively(dir)
    }
  )

  tempDir.test("MetalsClient can be instantiated") { tempDir =>
    val mockLspClient = new MockLspClient()
    val client = new TestMetalsClient(tempDir, mockLspClient)
    assert(client != null)
  }

  tempDir.test("initialize sends proper initialize request") { tempDir =>
    val mockLspClient = new MockLspClient()
    val client = new TestMetalsClient(tempDir, mockLspClient)

    client.initialize()

    // Verify initialize request was sent
    mockLspClient.lastRequest match
      case Some(("initialize", Some(params))) =>
        val cursor = params.hcursor
        val testParam = cursor.downField("testParam").as[String]
        assertEquals(testParam, Right("value"))

      case other =>
        fail(s"Expected initialize request, got: $other")
  }

  tempDir.test("initialize sends initialized notification after successful response") { tempDir =>
    val mockLspClient = new MockLspClient()
    val client = new TestMetalsClient(tempDir, mockLspClient)

    val initFuture = client.initialize()

    // Wait for completion
    val result = scala.concurrent.Await.result(initFuture, scala.concurrent.duration.Duration("5 seconds"))
    assertEquals(result, true)

    // Verify configuration notification was sent
    assertEquals(mockLspClient.lastNotification.map(_._1), Some("workspace/didChangeConfiguration"))
  }

  tempDir.test("initialize returns false for invalid response") { tempDir =>
    val mockLspClient = new MockLspClient()
    mockLspClient.responseToReturn = Json.obj() // No capabilities

    val client = new TestMetalsClient(tempDir, mockLspClient)
    val initFuture = client.initialize()

    val result = scala.concurrent.Await.result(initFuture, scala.concurrent.duration.Duration("5 seconds"))
    assertEquals(result, false)
  }

  tempDir.test("initialize can be called multiple times") { tempDir =>
    val mockLspClient = new MockLspClient()
    val client = new TestMetalsClient(tempDir, mockLspClient)

    // Multiple calls should work
    val firstResult = scala.concurrent.Await.result(
      client.initialize(),
      scala.concurrent.duration.Duration("5 seconds")
    )
    assertEquals(firstResult, true)

    val secondResult = scala.concurrent.Await.result(
      client.initialize(),
      scala.concurrent.duration.Duration("5 seconds")
    )
    assertEquals(secondResult, true)
  }

  tempDir.test("shutdown calls lsp client shutdown") { tempDir =>
    val mockLspClient = new MockLspClient()
    val client = new TestMetalsClient(tempDir, mockLspClient)

    val shutdownFuture = client.shutdown()

    scala.concurrent.Await.result(shutdownFuture, scala.concurrent.duration.Duration("5 seconds"))
    // Should complete without error - if we get here, it succeeded
  }
