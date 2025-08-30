package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import munit.FunSuite
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import kyo.*
import scala.concurrent.ExecutionContext

class LspClientTest extends FunSuite:

  implicit val ec: ExecutionContext = ExecutionContext.global

  class MockProcess(
      inputStream: InputStream = new ByteArrayInputStream(Array.empty),
      outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()
  ) extends java.lang.Process:
    override def getOutputStream   = outputStream
    override def getInputStream    = inputStream
    override def getErrorStream    = new ByteArrayInputStream(Array.empty)
    override def waitFor()         = 0
    override def exitValue()       = 0
    override def destroy()         = ()
    override def destroyForcibly() = this
    override def isAlive           = true
    override def pid()             = 12345L
    override def toHandle          = ???

  def createLspMessage(content: String): String =
    val contentBytes = content.getBytes(StandardCharsets.UTF_8)
    s"Content-Length: ${contentBytes.length}\r\n\r\n$content"

  test("LspClientK can be instantiated"):
    val mockProcess = new MockProcess()
    val client      = new LspClient(mockProcess)
    assert(client != null, "client should be constructed")

  test("sendNotification creates proper JSON-RPC notification (Kyo)"):
    import kyo.AllowUnsafe.embrace.danger
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    val params = Json.obj("test" -> "value".asJson)
    Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.sendNotification("test/notification", Some(params))))

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("Content-Length:"))
    assert(output.contains("\"method\":\"test/notification\""))
    assert(output.contains("\"jsonrpc\":\"2.0\""))
    assert(!output.contains("\"id\":"), "notification should not include id")

  test("sendNotification without params works (Kyo)"):
    import kyo.AllowUnsafe.embrace.danger
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.sendNotification("test/notification", None)))

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("\"method\":\"test/notification\""))
    assert(!output.contains("\"params\":"), "no params in notification")

  test("handles window/showMessage correctly (Kyo)"):
    import kyo.AllowUnsafe.embrace.danger
    val showMessageContent = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "method"  -> "window/showMessage".asJson,
        "params"  -> Json.obj(
          "type"    -> 3.asJson,
          "message" -> "Test message".asJson
        )
      )
      .noSpaces

    val inputStream = new ByteArrayInputStream(
      createLspMessage(showMessageContent).getBytes(StandardCharsets.UTF_8)
    )
    val mockProcess = new MockProcess(inputStream = inputStream)
    val client      = new LspClient(mockProcess)

    Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.start()))
    Thread.sleep(100)

  test("handles response messages correctly (Kyo)"):
    import kyo.AllowUnsafe.embrace.danger
    val responseContent = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "id"      -> 1.asJson,
        "result"  -> Json.obj("success" -> true.asJson)
      )
      .noSpaces

    val inputStream  = new ByteArrayInputStream(createLspMessage(responseContent).getBytes(StandardCharsets.UTF_8))
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(inputStream = inputStream, outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    // Start reader
    Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.start()))
    // Send a request that will be resolved by the preloaded response with id=1
    val result = Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.sendRequest("test/method", None)))
    assert(result != null, "request should complete with a JSON result")

  test("can send shutdown notification (Kyo)"):
    import kyo.AllowUnsafe.embrace.danger
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    Sync.Unsafe.evalOrThrow(KyoApp.runAndBlock(1.second)(client.sendNotification("shutdown", None)))
    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("\"method\":\"shutdown\""), "shutdown notification must be sent")

  test("message format validation (Kyo)"):
    val testMessage = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "method"  -> "test".asJson,
      "params"  -> Json.obj("key" -> "value".asJson)
    )

    val formatted = createLspMessage(testMessage.noSpaces)

    val headerEnd = formatted.indexOf("\r\n\r\n")
    val header    = formatted.substring(0, headerEnd)
    val body      = formatted.substring(headerEnd + 4)

    val contentLength = header
      .split("\r\n")
      .find(_.startsWith("Content-Length:"))
      .map(_.split(":")(1).trim.toInt)
      .getOrElse(0)

    assertEquals(contentLength, body.getBytes(StandardCharsets.UTF_8).length)

    parse(body) match
      case Right(json) =>
        assertEquals(json.hcursor.downField("method").as[String], Right("test"))
      case Left(error) =>
        fail(s"Failed to parse JSON: $error")
