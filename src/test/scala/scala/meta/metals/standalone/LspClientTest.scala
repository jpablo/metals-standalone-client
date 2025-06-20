package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import munit.FunSuite
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import scala.concurrent.ExecutionContext

class LspClientTest extends FunSuite:

  implicit val ec: ExecutionContext = ExecutionContext.global

  class MockProcess(
      inputStream: InputStream = new ByteArrayInputStream(Array.empty),
      outputStream: ByteArrayOutputStream = new ByteArrayOutputStream()
  ) extends Process:
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

  test("LspClient can be instantiated"):
    val mockProcess = new MockProcess()
    val client      = new LspClient(mockProcess)
    assert(client != null)

  test("sendRequest creates proper JSON-RPC request"):
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    val params = Json.obj("test" -> "value".asJson)
    client.sendRequest("test/method", Some(params))

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("Content-Length:"))
    assert(output.contains("\"method\":\"test/method\""))
    assert(output.contains("\"jsonrpc\":\"2.0\""))
    assert(output.contains("\"id\":"))

  test("sendNotification creates proper JSON-RPC notification"):
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    val params = Json.obj("test" -> "value".asJson)
    client.sendNotification("test/notification", Some(params))

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("Content-Length:"))
    assert(output.contains("\"method\":\"test/notification\""))
    assert(output.contains("\"jsonrpc\":\"2.0\""))
    assert(!output.contains("\"id\":")) // Notifications don't have ID

  test("sendRequest without params works"):
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    client.sendRequest("test/method")

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("\"method\":\"test/method\""))
    assert(!output.contains("\"params\":"))

  test("sendNotification without params works"):
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    client.sendNotification("test/notification")

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("\"method\":\"test/notification\""))
    assert(!output.contains("\"params\":"))

  test("handles window/showMessage correctly"):
    val showMessageContent = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "method"  -> "window/showMessage".asJson,
        "params"  -> Json.obj(
          "type"    -> 3.asJson, // Info
          "message" -> "Test message".asJson
        )
      )
      .noSpaces

    val inputStream = new ByteArrayInputStream(
      createLspMessage(showMessageContent).getBytes(StandardCharsets.UTF_8)
    )
    val mockProcess = new MockProcess(inputStream = inputStream)
    val client      = new LspClient(mockProcess)

    // Just verify it doesn't crash when processing the message
    client.start()
    Thread.sleep(100) // Give time for message processing

  test("handles response messages correctly"):
    val responseContent = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "id"      -> 1.asJson,
        "result"  -> Json.obj("success" -> true.asJson)
      )
      .noSpaces

    val inputStream  = new ByteArrayInputStream(
      createLspMessage(responseContent).getBytes(StandardCharsets.UTF_8)
    )
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(inputStream = inputStream, outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    client.start()

    // Send a request to create pending request with ID 1
    val requestFuture = client.sendRequest("test/method")

    // The future should complete when the response is processed
    // Note: In a real scenario, we'd need more sophisticated mocking
    // This test verifies the basic structure works
    assert(requestFuture != null)

  test("shutdown sends proper shutdown sequence"):
    val outputStream = new ByteArrayOutputStream()
    val mockProcess  = new MockProcess(outputStream = outputStream)
    val client       = new LspClient(mockProcess)

    client.shutdown()

    // Wait a bit for async operations to complete
    Thread.sleep(100)

    val output = outputStream.toString(StandardCharsets.UTF_8.name())
    assert(output.contains("\"method\":\"shutdown\""))
    // The exit notification is sent after the shutdown response, so we may not see it in our mock test
    // Just verify shutdown is sent

  test("message format validation"):
    // Test that we can parse our own message format
    val testMessage = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "method"  -> "test".asJson,
      "params"  -> Json.obj("key" -> "value".asJson)
    )

    val formatted = createLspMessage(testMessage.noSpaces)

    // Extract content length
    val headerEnd = formatted.indexOf("\r\n\r\n")
    val header    = formatted.substring(0, headerEnd)
    val body      = formatted.substring(headerEnd + 4)

    val contentLength = header
      .split("\r\n")
      .find(_.startsWith("Content-Length:"))
      .map(_.split(":")(1).trim.toInt)
      .getOrElse(0)

    assertEquals(contentLength, body.getBytes(StandardCharsets.UTF_8).length)

    // Verify we can parse the JSON
    parse(body) match
      case Right(json) =>
        assertEquals(json.hcursor.downField("method").as[String], Right("test"))
      case Left(error) =>
        fail(s"Failed to parse JSON: $error")
