file://<WORKSPACE>/src/main/scala/scala/meta/metals/standalone/LspClientK.scala
empty definition using pc, found symbol in pc: 
semanticdb not found
empty definition using fallback
non-local guesses:
	 -io/circe/fiber/interrupt.
	 -io/circe/fiber/interrupt#
	 -io/circe/fiber/interrupt().
	 -io/circe/parser/fiber/interrupt.
	 -io/circe/parser/fiber/interrupt#
	 -io/circe/parser/fiber/interrupt().
	 -io/circe/syntax/fiber/interrupt.
	 -io/circe/syntax/fiber/interrupt#
	 -io/circe/syntax/fiber/interrupt().
	 -kyo/fiber/interrupt.
	 -kyo/fiber/interrupt#
	 -kyo/fiber/interrupt().
	 -fiber/interrupt.
	 -fiber/interrupt#
	 -fiber/interrupt().
	 -scala/Predef.fiber.interrupt.
	 -scala/Predef.fiber.interrupt#
	 -scala/Predef.fiber.interrupt().
offset: 17081
uri: file://<WORKSPACE>/src/main/scala/scala/meta/metals/standalone/LspClientK.scala
text:
```scala
package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

import java.io.{OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

// Kyo imports for effects-based implementation
import kyo.{Process as _, *}

/** Minimal LSP client for communicating with Metals language server. Implements JSON-RPC 2.0 protocol over stdin/stdout.
  */
class LspClientK(process: Process)(using ExecutionContext):
  private val logger = Logger.getLogger(classOf[LspClientK].getName)

  private val requestId       = new AtomicInteger(0)
  private val pendingRequests = new ConcurrentHashMap[Int, Promise[Json]]()
  private val messageHandlers = new ConcurrentHashMap[String, Json => Option[Json]]()

  private val stdin  = new PrintWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8), true)
  private val stdout = process.getInputStream

  @volatile private var shutdownRequested = false

  setupMessageHandlers()

  private def setupMessageHandlers(): Unit =
    // Handle notifications and requests from server
    registerHandler("window/showMessage", handleShowMessage)
    registerHandler("window/showMessageRequest", handleShowMessageRequest)
    registerHandler("window/logMessage", handleLogMessage)
    registerHandler("textDocument/publishDiagnostics", handlePublishDiagnostics)
    registerHandler("workspace/applyEdit", handleApplyEdit)
    registerHandler("metals/status", handleMetalsStatus)
    registerHandler("metals/executeClientCommand", handleExecuteClientCommand)
    registerHandler("client/registerCapability", handleRegisterCapability)
    registerHandler("client/unregisterCapability", handleUnregisterCapability)
    registerHandler("window/workDoneProgress/create", handleProgressCreate)
    registerHandler("$/progress", handleProgress)
    registerHandler("workspace/configuration", handleConfiguration)

  private def registerHandler(method: String, handler: Json => Option[Json]): Unit =
    messageHandlers.put(method, handler): Unit

  def start(): Fiber[Exception, Unit] < (IO & Log) =
    for
      _               <- Log.info("Starting LSP client message reader...")
      _               <- Log.info("LSP message reader thread started")
      backgroundFiber <- Async.run(readMessages())
    yield backgroundFiber

  // Pure functions for LSP message parsing
  private def parseContentLength(header: String): Option[Int] =
    header
      .split("\r\n")
      .find(_.startsWith("Content-Length:"))
      .flatMap { line =>
        Try(line.split(":")(1).trim.toInt).toOption
      }

  private def findMessageBoundary(buffer: String): Option[Int] =
    val boundary = "\r\n\r\n"
    if buffer.contains(boundary) then Some(buffer.indexOf(boundary)) else None

  private def extractMessage(buffer: String, headerEnd: Int, contentLength: Int): Option[(String, String)] =
    val headerLength         = headerEnd + 4 // "\r\n\r\n".length
    val remainingAfterHeader = buffer.substring(headerLength)

    if remainingAfterHeader.length >= contentLength then
      val message         = remainingAfterHeader.substring(0, contentLength)
      val remainingBuffer = remainingAfterHeader.substring(contentLength)
      Some((message, remainingBuffer))
    else None

  private def readMessageBody(
      buffer:        String,
      headerEnd:     Int,
      contentLength: Int
  ) =
    def readLoop(currentBuffer: String): (updatedBuffer: String, success: Boolean, shouldContinue: Boolean) < (IO & Log & Async) =
      if currentBuffer.length >= headerEnd + 4 + contentLength then
        (updatedBuffer = currentBuffer, success = true, shouldContinue = true)
      else
        for
          needed    = (headerEnd + 4 + contentLength) - currentBuffer.length
          moreBytes = new Array[Byte](math.min(needed, 4096))
          moreBytesRead <- IO(stdout.read(moreBytes))
          result <- moreBytesRead match
            case -1 =>
              Log.warn("Unexpected end of stream while reading message body")
                .andThen(
                  (updatedBuffer = currentBuffer, success = false, shouldContinue = false)
                )
            case 0 =>
              Async.sleep(10.millis).andThen(readLoop(currentBuffer))
            case n =>
              readLoop(currentBuffer + new String(moreBytes, 0, n, StandardCharsets.UTF_8))
        yield result

    readLoop(buffer)

  def readMessages(): Unit < (IO & Log & Async & Abort[Exception]) =

    def mainLoop(buffer: String): Unit < (IO & Log & Async & Abort[Exception]) =
      if shutdownRequested || !process.isAlive then ()
      else
        for
          bytes = new Array[Byte](4096)
          bytesRead <- IO(stdout.read(bytes))
          _ <-
            bytesRead match
              case -1 => Log.warn("End of stream from Metals process")
              case 0  => Async.sleep(10.millis).andThen(mainLoop(buffer))
              case n  => processMessages(buffer + new String(bytes, 0, n, StandardCharsets.UTF_8))
        yield ()

    def processMessages(buffer: String): Unit < (IO & Log & Async & Abort[Exception]) =
      findMessageBoundary(buffer) match
        case None => mainLoop(buffer)
        case Some(headerEnd) =>
          val header = buffer.substring(0, headerEnd)
          parseContentLength(header) match
            case Some(contentLength) if contentLength > 0 =>
              for
                (updatedBuffer, readingBodySuccess, shouldContinueAfterRead) <-
                  readMessageBody(buffer, headerEnd, contentLength)
                _ <-
                  if !shouldContinueAfterRead then IO(())
                  else if readingBodySuccess then
                    extractMessage(updatedBuffer, headerEnd, contentLength) match
                      case Some((messageJson, remainingBuffer)) =>
                        for
                          _ <- Log.info(s"Raw LSP message received (length: $contentLength): $messageJson")
                          _ <- parseAndHandleMessageKyo(messageJson)
                          _ <- processMessages(remainingBuffer)
                        yield ()
                      case None =>
                        mainLoop(updatedBuffer)
                  else
                    mainLoop(updatedBuffer)
              yield ()
            case _ =>
              for
                _ <- Log.warn(s"Invalid or missing Content-Length in header: $header")
                _ <- processMessages(buffer.substring(headerEnd + 4))
              yield ()

    for
      _ <- Log.info("Starting to read messages from Metals process... (Kyo version)")
      _ <- Abort.catching[Exception](mainLoop(""))
    yield ()

  private def parseAndHandleMessageKyo(messageJson: String): Unit < (Log & IO & Abort[Exception]) =
    for
      _ <- Log.info(s"Parsing LSP message: ${messageJson.take(200)}${if messageJson.length > 200 then "..." else ""}")
      _ <- parse(messageJson) match
        case Right(json) =>
          for
            _ <- Log.info("Successfully parsed LSP message")
            _ <- handleMessage(json)
          yield ()
        case Left(error) =>
          for
            _ <- Log.error(s"Failed to parse JSON message: $error")
            _ <- Log.info(s"Raw message: $messageJson")
          yield ()
    yield ()

  private def handleMessage(message: Json): Unit < (Log & IO & Abort[Exception]) =
    val cursor = message.hcursor

    for
      _ <- Log.info(s"Processing message: ${message.noSpaces.take(100)}...")
      _ <- cursor.downField("id").as[Int] match
        case Right(id) if pendingRequests.containsKey(id) =>
          for
            _       <- Log.info(s"Received LSP response for request id: $id")
            promise <- IO(pendingRequests.remove(id))
            _ <- cursor.downField("result").as[Json] match
              case Right(result) =>
                for
                  _ <- Log.info(s"LSP request $id completed successfully")
                  _ <- Log.info(s"Response result: ${result.noSpaces.take(200)}...")
                  _ <- IO(promise.success(result))
                yield ()
              case Left(_) =>
                cursor.downField("error").as[Json] match
                  case Right(error) =>
                    for
                      _ <- Log.error(s"LSP request $id failed with error: $error")
                      _ <- IO(promise.failure(new RuntimeException(s"LSP error: $error")))
                    yield ()
                  case Left(_) =>
                    for
                      _ <- Log.error(s"LSP request $id failed - invalid response format")
                      _ <- Log.error(s"Raw response: ${message.noSpaces}")
                      _ <- IO(promise.failure(new RuntimeException("Invalid LSP response")))
                    yield ()
          yield ()
        case _ =>
          // This is a notification or request from server
          cursor.downField("method").as[String] match
            case Right(method) =>
              val params = cursor.downField("params").as[Json].getOrElse(Json.Null)
              for
                handlerOpt <- IO(Option(messageHandlers.get(method)))
                _ <- handlerOpt match
                  case Some(handler: (Json => Option[Json])) =>
                    for
                      result <- Abort.catching[Exception](IO(handler(params)))
                      _ <- cursor.downField("id").as[Int] match
                        case Right(msgId) =>
                          result match
                            case Some(responseData) => sendResponse(msgId, responseData)
                            case None               => sendResponse(msgId, Json.Null)
                        case Left(_) => IO(()) // This is a notification, no response needed
                    yield ()
                  case None =>
                    for
                      _ <- Log.warn(s"Unhandled LSP method: $method")
                      _ <- Log.info(s"Unhandled method params: $params")
                      _ <- cursor.downField("id").as[Int] match
                        case Right(msgId) =>
                          for
                            _ <- Log.warn(s"Unhandled method $method is a request (id: $msgId) - sending empty response")
                            _ <- sendResponse(msgId, Json.Null)
                          yield ()
                        case Left(_) =>
                          Log.info(s"Unhandled method $method is a notification - no response needed")
                    yield ()
              yield ()
            case Left(_) =>
              Log.warn(s"Message without method: $message")
    yield ()

  private def sendMessage(message: Json): Unit < (Log & IO & Abort[Exception]) =
    val messageStr   = message.noSpaces
    val messageBytes = messageStr.getBytes(StandardCharsets.UTF_8)
    val header       = s"Content-Length: ${messageBytes.length}\r\n\r\n"
    Abort.catching[Exception](
      for
        _ <- IO(stdin.print(header))
        _ <- IO(stdin.print(messageStr))
        _ <- IO(stdin.flush())
      yield ()
    )

  private def sendResponse(messageId: Int, result: Json) =
    sendMessage(
      Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id"      -> messageId.asJson,
        "result"  -> result
      )
    )

  private def sendErrorResponse(messageId: Int, error: String) =
    sendMessage(
      Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id"      -> messageId.asJson,
        "error" -> Json.obj(
          "code"    -> -32603.asJson, // Internal error
          "message" -> error.asJson
        )
      )
    )

  def sendRequest(method: String, params: Option[Json] = None): Json < (Log & IO & Async & Abort[Throwable]) =
    val promise = Promise[Json]()
    for
      id <- IO(requestId.incrementAndGet())
      _  <- Log.info(s"Sending LSP request: $method (id: $id)")
      _  <- IO(pendingRequests.put(id, promise))
      request = Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "id"      -> id.asJson,
        "method"  -> method.asJson
      ) deepMerge (params match
        case Some(p) => Json.obj("params" -> p)
        case None    => Json.obj())
      _      <- Log.info(s"LSP request JSON: ${request.noSpaces}")
      _      <- sendMessage(request)
      _      <- Log.info(s"LSP request sent: $method (id: $id)")
      result <- Async.fromFuture(promise.future)
    yield result

  private def handleShowMessage(params: Json): Option[Json] =
    val cursor      = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message     = cursor.downField("message").as[String].getOrElse("")

    val typeName = messageType match
      case 1 => "ERROR"
      case 2 => "WARN"
      case 3 => "INFO"
      case 4 => "LOG"
      case _ => "UNKNOWN"

    logger.info(s"[$typeName] $message")
    None

  private def handleShowMessageRequest(params: Json): Option[Json] =
    val cursor      = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message     = cursor.downField("message").as[String].getOrElse("")
    val actions     = cursor.downField("actions").as[List[Json]].getOrElse(Nil)

    val typeName = messageType match
      case 1 => "ERROR"
      case 2 => "WARN"
      case 3 => "INFO"
      case 4 => "LOG"
      case _ => "UNKNOWN"

    logger.info(s"[$typeName] $message")

    // Auto-select the first action for headless operation
    if actions.nonEmpty then
      val selectedAction = actions.head
      val actionTitle    = selectedAction.hcursor.downField("title").as[String].getOrElse("Unknown")
      logger.info(s"Auto-selecting action: $actionTitle")
      Some(selectedAction)
    else Some(Json.Null)

  private def handleLogMessage(params: Json): Option[Json] =
    val cursor      = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message     = cursor.downField("message").as[String].getOrElse("")

    if messageType <= 2 then // Error or Warning
      logger.warning(s"Metals: $message")
    else logger.info(s"Metals: $message")
    None

  private def handlePublishDiagnostics(params: Json): Option[Json] =
    val cursor      = params.hcursor
    val uri         = cursor.downField("uri").as[String].getOrElse("")
    val diagnostics = cursor.downField("diagnostics").as[List[Json]].getOrElse(Nil)

    if diagnostics.nonEmpty then logger.info(s"Diagnostics for $uri: ${diagnostics.length} issues")
    None

  private def handleApplyEdit(_params: Json): Option[Json] =
    // Just return success - we're not actually applying edits
    Some(Json.obj("applied" -> true.asJson))

  private def handleMetalsStatus(params: Json): Option[Json] =
    val text = params.hcursor.downField("text").as[String].getOrElse("")
    if text.nonEmpty then logger.info(s"Metals status: $text")
    None

  private def handleExecuteClientCommand(params: Json): Option[Json] =
    val command = params.hcursor.downField("command").as[String].getOrElse("")
    logger.info(s"Client command: $command")
    None

  private def handleRegisterCapability(_params: Json): Option[Json] =
    Some(Json.Null)

  private def handleUnregisterCapability(_params: Json): Option[Json] =
    Some(Json.Null)

  private def handleProgressCreate(_params: Json): Option[Json] =
    Some(Json.Null)

  private def handleProgress(params: Json): Option[Json] =
    // Ignore progress notifications
    None

  private def handleConfiguration(params: Json): Option[Json] =
    val cursor = params.hcursor
    val items  = cursor.downField("items").as[List[Json]].getOrElse(Nil)

    // Return configuration for metals
    val configs = items.map { item =>
      val section = item.hcursor.downField("section").as[String].getOrElse("")
      if section == "metals" then
        Json.obj(
          "startMcpServer"               -> true.asJson,
          "isHttpEnabled"                -> true.asJson, // Required for MCP server
          "statusBarProvider"            -> "off".asJson,
          "inputBoxProvider"             -> false.asJson,
          "quickPickProvider"            -> false.asJson,
          "executeClientCommandProvider" -> false.asJson,
          "isExitOnShutdown"             -> true.asJson
        )
      else Json.obj()
    }

    Some(configs.asJson)

  def shutdown(fiber: Fiber[Exception, Unit]) =
    // val result: Result[Throwable, Json] < (Log & Async) =
    //   Abort.run(sendRequest("shutdown"))

    val x =
      for
        result <- Abort.run(sendRequest("shutdown"))
        _ <- result match
          case Result.Success(_) =>
            for
              _ <- Log.info("Shutdown request sent successfully")
              _ <- sendNotification("exit")
              _ <- Abort.catching[Throwable](IO(stdin.close()).andThen(IO(stdout.close())))
              _ <- fiber.interr@@upt()
              _ <- IO {
                if process.isAlive then
                  process.destroy()
                  // Give it a moment for graceful shutdown
                  Thread.sleep(1000)
                  if process.isAlive then process.destroyForcibly()
              }
            yield ()
          case Result.Failure(e) =>
            for
              _ <- Log.warn(s"Failed to send shutdown request: $e")
              _ <- Abort.catching[Throwable](IO(stdin.close()).andThen(IO(stdout.close())))
              _ <- IO(process.destroyForcibly())
            yield ()
      yield ()

    Abort.runPartial(sendRequest("shutdown")) match
      case Result.Success(_) =>
        for
          _ <- sendNotification("exit")
          _ <- Abort.catching[Throwable](
            IO(stdin.close()).andThen(IO(stdout.close()))
          )
          _ <- IO {
            if process.isAlive then
              process.destroy()
              // Give it a moment for graceful shutdown
              Thread.sleep(1000)
              if process.isAlive then process.destroyForcibly(): Unit
          }
        yield ()
      case Result.Failure(e) =>
        for
          _ <- Log.warn(s"Shutdown request panicked: $e")
          _ <- Abort.catching[Throwable](
            IO(stdin.close()).andThen(IO(stdout.close()))
          )
          _ <- IO(process.destroyForcibly())
        yield ()

  private def sendNotification(method: String, params: Option[Json] = None): Unit < (Log & IO & Abort[Throwable]) =
    val notification =
      Json.obj(
        "jsonrpc" -> "2.0".asJson,
        "method"  -> method.asJson
      ).deepMerge(
        params match
          case Some(p) => Json.obj("params" -> p)
          case None    => Json.obj()
      )

    sendMessage(notification)

```


#### Short summary: 

empty definition using pc, found symbol in pc: 