package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import kyo.*

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors}
import java.util.logging.Logger
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.Try

/** Minimal LSP client for communicating with Metals language server. Implements JSON-RPC 2.0 protocol over
  * stdin/stdout.
  */
class LspClient(process: java.lang.Process)(using ExecutionContext):
  private val logger = Logger.getLogger(classOf[LspClient].getName)

  private val requestId       = new AtomicInteger(0)
  private val pendingRequests = new ConcurrentHashMap[Int, Promise[Json]]()
  private val messageHandlers = new ConcurrentHashMap[String, Json => Option[Json]]()

  private val stdin   = new PrintWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8), true)
  private val stdout  = process.getInputStream
  private val stderr  = process.getErrorStream

  @volatile private var shutdownRequested     = false
  private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor(r =>
    val thread = new Thread(r, "lsp-reader")
    thread.setDaemon(true)
    thread
  )
//  private val stderrExecutor: ExecutorService = Executors.newSingleThreadExecutor(r =>
//    val thread = new Thread(r, "lsp-stderr-reader")
//    thread.setDaemon(true)
//    thread
//  )

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

  def start(): Unit < (Async & Abort[Throwable] & Sync) =
    val started = Promise[Unit]()
    for
      _ <- Log.info("Starting LSP client message reader...")
      _ <- Sync.defer {
        readerExecutor.submit(
          new Runnable:
            def run(): Unit =
              logger.info("LSP message reader thread started")
              started.success(())
              readMessages()
        )
        ()
      }
      _ <- Async.fromFuture(started.future)
    yield ()

  private def readMessages(): Unit =
    var buffer = ""
    logger.info("Starting to read messages from Metals process...")

    try
      var shouldContinue = true
      while !shutdownRequested && process.isAlive && shouldContinue do
        println("-- 1 --")
        // Read available data in chunks
        val bytes     = new Array[Byte](4096)
        println("-- 1.1 --")
        val bytesRead = stdout.read(bytes)
        println("-- 1.2 --")

        if bytesRead == -1 then
          logger.warning("End of stream from Metals process")
          shouldContinue = false
        else if bytesRead == 0 then
          println("-- 2 --")
          Thread.sleep(10)
        else
          val data = new String(bytes, 0, bytesRead, StandardCharsets.UTF_8)
          println("-- 3 --")
          println(data)
          buffer += data

          // Process complete messages
          var processMessages = true
          while buffer.contains("\r\n\r\n") && processMessages do
            val headerEnd = buffer.indexOf("\r\n\r\n")
            val header    = buffer.substring(0, headerEnd)
            buffer = buffer.substring(headerEnd + 4)

            // Parse content length from header
            var contentLength = 0
            for line <- header.split("\r\n") do
              if line.startsWith("Content-Length:") then contentLength = line.split(":")(1).trim.toInt

            if contentLength > 0 then
              // Wait for complete message body
              var readingBody = true
              while buffer.length < contentLength && readingBody do
                val needed        = contentLength - buffer.length
                val moreBytes     = new Array[Byte](math.min(needed, 4096))
                val moreBytesRead = stdout.read(moreBytes)

                if moreBytesRead == -1 then
                  logger.warning("Unexpected end of stream while reading message body")
                  readingBody = false
                  processMessages = false
                  shouldContinue = false
                else if moreBytesRead == 0 then Thread.sleep(10)
                else
                  val moreData = new String(moreBytes, 0, moreBytesRead, StandardCharsets.UTF_8)
                  buffer += moreData

              if buffer.length >= contentLength then
                val messageJson = buffer.substring(0, contentLength)
                buffer = buffer.substring(contentLength)

                logger.fine(s"Raw LSP message received (length: $contentLength): $messageJson")
                parseAndHandleMessage(messageJson)
    catch
      case e: Exception if !shutdownRequested =>
        logger.severe(s"Error reading messages: ${e.getMessage}")

  private def parseAndHandleMessage(messageJson: String): Unit =
    logger.fine(s"Parsing LSP message: ${messageJson.take(200)}${if messageJson.length > 200 then "..." else ""}")
    parse(messageJson) match
      case Right(json) =>
        logger.fine("Successfully parsed LSP message")
        handleMessage(json)
      case Left(error) =>
        logger.severe(s"Failed to parse JSON message: $error")
        logger.fine(s"Raw message: $messageJson")

  private def handleMessage(message: Json): Unit =
    val cursor = message.hcursor

    logger.fine(s"Processing message: ${message.noSpaces.take(100)}...")

    // Check if this is a response to our request
    cursor.downField("id").as[Int] match
      case Right(id) if pendingRequests.containsKey(id) =>
        logger.fine(s"Received LSP response for request id: $id")
        val promise = pendingRequests.remove(id)
        cursor.downField("result").as[Json] match
          case Right(result) =>
            logger.fine(s"LSP request $id completed successfully")
            logger.fine(s"Response result: ${result.noSpaces.take(200)}...")
            promise.success(result)
          case Left(_)       =>
            cursor.downField("error").as[Json] match
              case Right(error) =>
                logger.severe(s"LSP request $id failed with error: $error")
                promise.failure(new RuntimeException(s"LSP error: $error"))
              case Left(_)      =>
                logger.severe(s"LSP request $id failed - invalid response format")
                logger.severe(s"Raw response: ${message.noSpaces}")
                promise.failure(new RuntimeException("Invalid LSP response"))

      case _ =>
        // This is a notification or request from server
        cursor.downField("method").as[String] match
          case Right(method) =>
            val params = cursor.downField("params").as[Json].getOrElse(Json.Null)

            Option(messageHandlers.get(method)) match
              case Some(handler) =>
                try
                  val result = handler(params)

                  // If this is a request (has id), send response
                  cursor.downField("id").as[Int] match
                    case Right(msgId) =>
                      result match
                        case Some(responseData) => sendResponse(msgId, responseData)
                        case None               => sendResponse(msgId, Json.Null)
                    case Left(_)      => // This is a notification, no response needed
                catch
                  case e: Exception =>
                    logger.severe(s"Error handling $method: ${e.getMessage}")
                    cursor.downField("id").as[Int] match
                      case Right(msgId) => sendErrorResponse(msgId, e.getMessage)
                      case Left(_)      => // Notification, can't send error response
              case None          =>
                logger.fine(s"Unhandled LSP method: $method")
                logger.fine(s"Unhandled method params: $params")
                // Check if this is a request (has id) that needs a response
                cursor.downField("id").as[Int] match
                  case Right(msgId) =>
                    logger.fine(s"Unhandled method $method is a request (id: $msgId) - sending empty response")
                    sendResponse(msgId, Json.Null)
                  case Left(_)      =>
                    logger.fine(s"Unhandled method $method is a notification - no response needed")

          case Left(_) =>
            logger.warning(s"Message without method: $message")

  private def sendMessage(message: Json): Unit =
    val messageStr   = message.noSpaces
    val messageBytes = messageStr.getBytes(StandardCharsets.UTF_8)
    val header       = s"Content-Length: ${messageBytes.length}\r\n\r\n"
    try
      stdin.print(header)
      stdin.print(messageStr)
      stdin.flush()
      println("---------------")
      println(header)
      println(messageStr)
    catch
      case e: Exception =>
        logger.severe(s"Failed to send message: ${e.getMessage}")

  private def sendResponse(messageId: Int, result: Json): Unit =
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> messageId.asJson,
      "result"  -> result
    )
    sendMessage(response)

  private def sendErrorResponse(messageId: Int, error: String): Unit =
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> messageId.asJson,
      "error"   -> Json.obj(
        "code"    -> -32603.asJson, // Internal error
        "message" -> error.asJson
      )
    )
    sendMessage(response)

  def sendRequest(method: String, params: Option[Json] = None): Json < (Async & Abort[Throwable] & Sync) =
    val id      = requestId.incrementAndGet()
    val promise = Promise[Json]()

    for
      _ <- Log.debug(s"Sending LSP request: $method (id: $id)")
      _ <- Sync.defer(pendingRequests.put(id, promise))
      request = Json
        .obj(
          "jsonrpc" -> "2.0".asJson,
          "id"      -> id.asJson,
          "method"  -> method.asJson
        )
        .deepMerge(params match
          case Some(p) => Json.obj("params" -> p)
          case None    => Json.obj()
        )
      _ <- Log.debug(s"LSP request JSON: ${request.noSpaces}")
      _ <- Sync.defer(sendMessage(request))
      _ <- Log.debug(s"LSP request sent: $method (id: $id)")
      res <- Async.fromFuture(promise.future)
    yield res

  def sendNotification(method: String, params: Option[Json] = None): Unit < Sync =
    val notification = Json
      .obj(
        "jsonrpc" -> "2.0".asJson,
        "method"  -> method.asJson
      )
      .deepMerge(params match
        case Some(p) => Json.obj("params" -> p)
        case None    => Json.obj()
      )

    Sync.defer(sendMessage(notification))

  // Message handlers (implementing minimal MetalsLanguageClient interface)

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

  def shutdown(): Unit < (Async & Abort[Throwable] & Sync) =
    for
      _ <- Sync.defer { shutdownRequested = true }
      _ <- sendRequest("shutdown").map(_ => ())
      _ <- sendNotification("exit")
      _ <- Sync.defer {
        // Close streams and terminate process
        val _ = Try(stdin.close())
        val _2 = Try(stdout.close())
        val _3 = Try(stderr.close())
        ()
      }
      _ <- Sync.defer(readerExecutor.shutdown())
      _ <-
        if process.isAlive then
          for
            _ <- Sync.defer(process.destroy())
            _ <- Async.sleep(1.second)
            _ <- if process.isAlive then Sync.defer(process.destroyForcibly()).map(_ => ()) else Sync.defer(())
          yield ()
        else Sync.defer(())
    yield ()
