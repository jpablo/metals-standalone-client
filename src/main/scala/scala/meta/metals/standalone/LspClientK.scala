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

/** Kyo-based LSP client for communicating with Metals over JSON-RPC 2.0 (stdin/stdout).
  *
  * Exposes effectful APIs using Kyo. Intended to be adopted incrementally alongside the
  * original Future-based LspClient.
  */
class LspClientK(process: java.lang.Process)(using Frame):

  private val requestId       = new AtomicInteger(0)
  private val pendingRequests = new ConcurrentHashMap[Int, java.util.concurrent.CompletableFuture[Json]]()
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
    messageHandlers.put(method, handler)

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
    def readLoop(buffer: String): Unit < (Async & Sync) =
      if shutdownRequested || !process.isAlive() then Sync.defer(())
      else
        for
          bytes <- Sync.defer(new Array[Byte](4096))
          read  <- Sync.defer(stdout.read(bytes))
          _     <-
            if read == -1 then Log.warn("End of stream from Metals process")
            else if read == 0 then Sync.defer(Thread.sleep(10))
            else Sync.defer(())
          newBuf = if read > 0 then buffer + new String(bytes, 0, read, StandardCharsets.UTF_8) else buffer
          rem   <- processMessages(newBuf)
          _     <- readLoop(rem)
        yield ()

    def processMessages(buf: String): String < Sync =
      Sync.defer {
        var buffer = buf
        var done   = false
        while buffer.contains("\r\n\r\n") && !done do
          val headerEnd = buffer.indexOf("\r\n\r\n")
          val header    = buffer.substring(0, headerEnd)
          var contentLength = 0
          header.split("\r\n").foreach { line =>
            if line.startsWith("Content-Length:") then contentLength = line.split(":")(1).trim.toInt
          }
          val afterHeader = buffer.substring(headerEnd + 4)
          if afterHeader.length >= contentLength then
            val messageJson = afterHeader.substring(0, contentLength)
            val rest        = afterHeader.substring(contentLength)
            // Handle synchronously
            Sync.Unsafe.evalOrThrow(handleRawMessage(messageJson))
            buffer = rest
          else done = true
        buffer
      }

    readLoop("")

  private def handleRawMessage(messageJson: String): Unit < Sync =
    val preview = messageJson.take(200) + (if messageJson.length > 200 then "..." else "")
    Log.info(s"Parsing LSP message: $preview").andThen {
      parse(messageJson) match
        case Right(json) => Sync.defer { handleMessage(json); () }
        case Left(error) => Log.error(s"Failed to parse JSON message: $error").andThen(Log.info(s"Raw message: $messageJson"))
    }

  private def handleMessage(message: Json): Unit =
    val cursor = message.hcursor
    cursor.downField("id").as[Int] match
      case Right(id) if pendingRequests.containsKey(id) =>
        val promise = pendingRequests.remove(id)
        cursor.downField("result").as[Json] match
          case Right(result) => promise.complete(java.util.Optional.of(result).orElse(null))
          case Left(_)       =>
            cursor.downField("error").as[Json] match
              case Right(error) => promise.completeExceptionally(new RuntimeException(s"LSP error: $error"))
              case Left(_)      => promise.completeExceptionally(new RuntimeException("Invalid LSP response"))
      case _ =>
        cursor.downField("method").as[String] match
          case Right(method) =>
            val params = cursor.downField("params").as[Json].getOrElse(Json.Null)
            Option(messageHandlers.get(method)).foreach { handler =>
              val result = handler(params)
              cursor.downField("id").as[Int] match
                case Right(msgId) =>
                  result match
                    case Some(responseData) => sendResponse(msgId, responseData)
                    case None               => sendResponse(msgId, Json.Null)
                case Left(_)      => ()
            }
          case Left(_) => ()

  private def sendMessage(message: Json): Unit < Sync =
    Sync.defer {
      val messageStr   = message.noSpaces
      val messageBytes = messageStr.getBytes(StandardCharsets.UTF_8)
      val header       = s"Content-Length: ${messageBytes.length}\r\n\r\n"
      stdin.print(header)
      stdin.print(messageStr)
      stdin.flush()
    }

  private def sendResponse(messageId: Int, result: Json): Unit =
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id"      -> messageId.asJson,
      "result"  -> result
    )
    // Fire and forget in Sync
    Sync.Unsafe.evalOrThrow(sendMessage(response))

  def sendRequest(method: String, params: Option[Json] = None): Json < (Async & Sync) =
    val id      = requestId.incrementAndGet()
    val promise = new java.util.concurrent.CompletableFuture[Json]()
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
  private def handleShowMessageRequest(params: Json): Option[Json] = Some(Json.Null)
  private def handleLogMessage(params: Json): Option[Json] = None
  private def handlePublishDiagnostics(params: Json): Option[Json] = None
  private def handleApplyEdit(_params: Json): Option[Json] = Some(Json.obj("applied" -> true.asJson))
  private def handleMetalsStatus(params: Json): Option[Json] = None
  private def handleExecuteClientCommand(params: Json): Option[Json] = None
  private def handleRegisterCapability(_params: Json): Option[Json] = Some(Json.Null)
  private def handleUnregisterCapability(_params: Json): Option[Json] = Some(Json.Null)
  private def handleProgressCreate(_params: Json): Option[Json] = Some(Json.Null)
  private def handleProgress(params: Json): Option[Json] = None
  private def handleConfiguration(params: Json): Option[Json] = Some(Json.arr())

  def shutdown(): Unit < (Async & Sync) =
    for
      _ <- sendRequest("shutdown").map(_ => ())
      _ <- sendNotification("exit")
      _ <- Sync.defer {
        shutdownRequested = true
        try stdin.close() finally stdout.close()
        ()
      }
    yield ()
