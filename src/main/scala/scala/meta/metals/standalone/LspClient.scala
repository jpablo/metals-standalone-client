package scala.meta.metals.standalone

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*
import kyo.*
import kyo.AllowUnsafe.embrace.danger
import kyo.Log

import java.io.{OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CompletableFuture

/** Kyo-based LSP client for communicating with Metals over JSON-RPC 2.0 (stdin/stdout).
  *
  * Exposes effectful APIs using Kyo. Intended to be adopted incrementally alongside the
  * original Future-based LspClient.
  */
class LspClient(process: java.lang.Process)(using Frame):

  private val debugIo = sys.props.get("LSP_DEBUG_IO").isDefined

  private val requestId       = new AtomicInteger(0)
  private val pendingRequests = new ConcurrentHashMap[Int, CompletableFuture[Json]]()
  private val messageHandlers = new ConcurrentHashMap[String, Json => Option[Json]]()

  private val stdin  = new PrintWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8), true)
  private val stdout = process.getInputStream

  @volatile private var shutdownRequested = false

  setupMessageHandlers()

  private def setupMessageHandlers(): Unit =
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
    val _ = messageHandlers.put(method, handler)

  def start(): Unit < Sync =
    Log.info("Starting Kyo LSP client message reader...").andThen {
      Sync.defer {
        val runnable = new Runnable {
          override def run(): Unit =
            // Run the async reader to completion within this thread
            kyo.Sync.Unsafe.evalOrThrow(kyo.KyoApp.runAndBlock(kyo.Duration.Infinity)(readMessages()))
        }
        val t = new Thread(runnable, "lsp-reader-kyo")
        t.setDaemon(true)
        t.start()
        ()
      }
    }

  private def readMessages(): Unit < (Async & Sync) =
    Sync.defer {
      val sb = new StringBuilder
      var continue = true
      while !shutdownRequested && process.isAlive() && continue do
        val bytes    = new Array[Byte](4096)
        val bytesRead = stdout.read(bytes)
        if bytesRead == -1 then
          Sync.Unsafe.evalOrThrow(logEndOfStream())
          shutdownRequested = true
          continue = false
        else if bytesRead == 0 then Thread.sleep(10)
        else sb.append(new String(bytes, 0, bytesRead, StandardCharsets.UTF_8))

        var processMore = true
        while processMore && sb.indexOf("\r\n\r\n") >= 0 do
          val headerEnd = sb.indexOf("\r\n\r\n")
          val header    = sb.substring(0, headerEnd)
          // remove header from buffer
          sb.delete(0, headerEnd + 4)

          var contentLength = 0
          header.split("\r\n").foreach { line =>
            if line.startsWith("Content-Length:") then contentLength = line.split(":")(1).trim.toInt
          }

          if contentLength > 0 then
            var haveBody = sb.length >= contentLength
            while !haveBody && !shutdownRequested && process.isAlive() && continue do
              val need      = contentLength - sb.length
              val moreBytes = new Array[Byte](math.min(need, 4096))
              val r         = stdout.read(moreBytes)
              if r == -1 then
                Sync.Unsafe.evalOrThrow(logEndOfStream())
                shutdownRequested = true
                continue = false
                processMore = false
              else if r == 0 then Thread.sleep(10)
              else sb.append(new String(moreBytes, 0, r, StandardCharsets.UTF_8))
              haveBody = sb.length >= contentLength

            if sb.length >= contentLength then
              val messageJson = sb.substring(0, contentLength)
              sb.delete(0, contentLength)
              // Handle synchronously
              Sync.Unsafe.evalOrThrow(handleRawMessage(messageJson))
            else
              // incomplete; prepend header back for next round
              sb.insert(0, header + "\r\n\r\n")
              processMore = false
          else
            // header without content length; skip
            ()
    }

  private def handleRawMessage(messageJson: String): Unit < Sync =
    val preview = messageJson.take(200) + (if messageJson.length > 200 then "..." else "")
    if debugIo then java.lang.System.err.println(s"<<< $preview")
    Log.info(s"Parsing LSP message: $preview").andThen {
      parse(messageJson) match
        case Right(json) => Sync.defer { handleMessage(json); () }
        case Left(error) =>
          Log.error(s"Failed to parse JSON message: $error").andThen(Log.info(s"Raw message: $messageJson"))
    }

  private def handleMessage(message: Json): Unit =
    val cursor = message.hcursor
    // Response to a client-initiated request
    cursor.downField("id").as[Int] match
      case Right(id) if pendingRequests.containsKey(id) =>
        val promise = pendingRequests.remove(id)
        cursor.downField("result").as[Json] match
          case Right(result) => val _ = promise.complete(result)
          case Left(_)       =>
            cursor.downField("error").as[Json] match
              case Right(error) => val _ = promise.completeExceptionally(new RuntimeException(s"LSP error: $error"))
              case Left(_)      => val _ = promise.completeExceptionally(new RuntimeException("Invalid LSP response"))
      case _ =>
        // Notification or server-initiated request
        cursor.downField("method").as[String] match
          case Right(method) =>
            val params = cursor.downField("params").as[Json].getOrElse(Json.Null)
            Option(messageHandlers.get(method)) match
              case Some(handler) =>
                try
                  val result = handler(params)
                  // If there's an id, it's a request: send a response
                  cursor.downField("id").as[Int] match
                    case Right(msgId) =>
                      result match
                        case Some(responseData) => sendResponse(msgId, responseData)
                        case None               => sendResponse(msgId, Json.Null)
                    case Left(_)      => () // notification: no response
                catch
                  case e: Exception =>
                    cursor.downField("id").as[Int] match
                      case Right(msgId) => sendErrorResponse(msgId, e.getMessage)
                      case Left(_)      => ()
              case None =>
                cursor.downField("id").as[Int] match
                  case Right(msgId) =>
                    sendResponse(msgId, Json.Null)
                  case Left(_) => () // notification: ignore
          case Left(_) => ()

  private def sendMessage(message: Json): Unit < Sync =
    Sync.defer {
      val messageStr   = message.noSpaces
      val messageBytes = messageStr.getBytes(StandardCharsets.UTF_8)
      val header       = s"Content-Length: ${messageBytes.length}\r\n\r\n"
      try
        if debugIo then java.lang.System.err.println(s">>> ${messageStr.take(200)}${if messageStr.length > 200 then "..." else ""}")
        stdin.print(header)
        stdin.print(messageStr)
        stdin.flush()
      catch
        case e: Exception => Log.error(s"Failed to send message: ${e.getMessage}")
    }

  private def sendResponse(messageId: Int, result: Json): Unit =
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> messageId.asJson,
      "result"  -> result
    )
    // Fire and forget in Sync
    Sync.Unsafe.evalOrThrow(sendMessage(response))

  private def sendErrorResponse(messageId: Int, error: String): Unit =
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> messageId.asJson,
      "error"   -> Json.obj(
        "code"    -> (-32603).asJson, // Internal error
        "message" -> error.asJson
      )
    )
    Sync.Unsafe.evalOrThrow(sendMessage(response))

  def sendRequest(method: String, params: Option[Json] = None): Json < (Async & Sync) =
    val id      = requestId.incrementAndGet()
    val promise = new CompletableFuture[Json]()
    pendingRequests.put(id, promise)

    val request = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> id.asJson,
      "method"  -> method.asJson
    ) deepMerge (params match
      case Some(p) => Json.obj("params" -> p)
      case None    => Json.obj()
    )
    for
      _ <- sendMessage(request)
      r <- Async.fromFuture(promise)
    yield r

  def sendNotification(method: String, params: Option[Json] = None): Unit < Sync =
    val notification = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "method"  -> method.asJson
    ) deepMerge (params match
      case Some(p) => Json.obj("params" -> p)
      case None    => Json.obj()
    )
    sendMessage(notification)

  // Handlers (minimal Metals language client)
  private def handleShowMessage(params: Json): Option[Json] = None

  private def handleShowMessageRequest(params: Json): Option[Json] =
    val actions = params.hcursor.downField("actions").as[List[Json]].getOrElse(Nil)
    if actions.nonEmpty then Some(actions.head) else Some(Json.Null)

  private def handleLogMessage(params: Json): Option[Json] = None

  private def handlePublishDiagnostics(params: Json): Option[Json] = None
  private def handleApplyEdit(_params: Json): Option[Json] = Some(Json.obj("applied" -> true.asJson))
  private def handleMetalsStatus(params: Json): Option[Json] = None

  private def handleExecuteClientCommand(params: Json): Option[Json] = None

  private def handleRegisterCapability(_params: Json): Option[Json] = Some(Json.Null)
  private def handleUnregisterCapability(_params: Json): Option[Json] = Some(Json.Null)
  private def handleProgressCreate(_params: Json): Option[Json] = Some(Json.Null)
  private def handleProgress(params: Json): Option[Json] = None

  private def handleConfiguration(params: Json): Option[Json] =
    val cursor = params.hcursor
    val items  = cursor.downField("items").as[List[Json]].getOrElse(Nil)
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

  def shutdown(): Unit < (Async & Sync) =
    for
      _ <- sendRequest("shutdown").map(_ => ())
      _ <- sendNotification("exit")
      _ <- Sync.defer {
        shutdownRequested = true
        try stdin.close() finally stdout.close()
        ()
      }
      _ <- Sync.defer {
        // Align with main: terminate the process gracefully, then forcibly
        try process.destroy() finally ()
        Thread.sleep(1000)
        if process.isAlive() then process.destroyForcibly() else ()
        ()
      }
    yield ()
  private def logEndOfStream(): Unit < Sync =
    if sys.props.get("LSP_TEST_QUIET").isDefined then
      Log.debug("End of stream from Metals process")
    else
      Log.warn("End of stream from Metals process")
