package scala.meta.metals.standalone

import io.circe._
import io.circe.parser._
import io.circe.syntax._

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors}
import java.util.logging.Logger
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Minimal LSP client for communicating with Metals language server.
 * Implements JSON-RPC 2.0 protocol over stdin/stdout.
 */
class LspClient(process: Process)(implicit ec: ExecutionContext) {
  private val logger = Logger.getLogger(classOf[LspClient].getName)
  
  private val requestId = new AtomicInteger(0)
  private val pendingRequests = new ConcurrentHashMap[Int, Promise[Json]]()
  private val messageHandlers = new ConcurrentHashMap[String, Json => Option[Json]]()
  
  private val stdin = new PrintWriter(new OutputStreamWriter(process.getOutputStream, StandardCharsets.UTF_8), true)
  private val stdout = new BufferedReader(new InputStreamReader(process.getInputStream, StandardCharsets.UTF_8))
  
  @volatile private var shutdownRequested = false
  private val readerExecutor: ExecutorService = Executors.newSingleThreadExecutor(r => {
    val thread = new Thread(r, "lsp-reader")
    thread.setDaemon(true)
    thread
  })

  setupMessageHandlers()
  
  private def setupMessageHandlers(): Unit = {
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
  }

  private def registerHandler(method: String, handler: Json => Option[Json]): Unit = {
    messageHandlers.put(method, handler)
  }

  def start(): Future[Unit] = {
    val promise = Promise[Unit]()
    logger.info("Starting LSP client message reader...")
    
    readerExecutor.submit(new Runnable {
      def run(): Unit = {
        logger.info("LSP message reader thread started")
        promise.success(())
        readMessages()
      }
    })
    
    promise.future
  }

  private def readMessages(): Unit = {
    var buffer = new StringBuilder()
    logger.info("Starting to read messages from Metals process...")
    
    try {
      while (!shutdownRequested && process.isAlive) {
        val line = stdout.readLine()
        if (line == null) {
          logger.warning("Received null line from Metals process - end of stream")
          return
        }
        
        buffer.append(line).append("\r\n")
        
        // Check for complete message (header + body separated by \r\n\r\n)
        val content = buffer.toString()
        val headerEnd = content.indexOf("\r\n\r\n")
        
        if (headerEnd >= 0) {
          val header = content.substring(0, headerEnd)
          val remaining = content.substring(headerEnd + 4)
          
          // Parse content length from header
          val contentLength = parseContentLength(header)
          
          if (contentLength > 0 && remaining.length >= contentLength) {
            val messageJson = remaining.substring(0, contentLength)
            val leftover = remaining.substring(contentLength)
            
            // Log all raw messages received
            logger.info(s"Raw LSP message received (length: $contentLength): $messageJson")
            
            // Process the message
            parseAndHandleMessage(messageJson)
            
            // Keep leftover for next iteration
            buffer = new StringBuilder(leftover)
          }
        }
      }
    } catch {
      case e: Exception if !shutdownRequested =>
        logger.severe(s"Error reading messages: ${e.getMessage}")
    }
  }

  private def parseContentLength(header: String): Int = {
    header.split("\r\n")
      .find(_.startsWith("Content-Length:"))
      .map(_.substring("Content-Length:".length).trim.toInt)
      .getOrElse(0)
  }

  private def parseAndHandleMessage(messageJson: String): Unit = {
    logger.info(s"Parsing LSP message: ${messageJson.take(200)}${if (messageJson.length > 200) "..." else ""}")
    parse(messageJson) match {
      case Right(json) =>
        logger.info("Successfully parsed LSP message")
        handleMessage(json)
      case Left(error) =>
        logger.severe(s"Failed to parse JSON message: $error")
        logger.info(s"Raw message: $messageJson")
    }
  }

  private def handleMessage(message: Json): Unit = {
    val cursor = message.hcursor
    
    // Check if this is a response to our request
    cursor.downField("id").as[Int] match {
      case Right(id) if pendingRequests.containsKey(id) =>
        logger.info(s"Received LSP response for request id: $id")
        val promise = pendingRequests.remove(id)
        cursor.downField("result").as[Json] match {
          case Right(result) => 
            logger.info(s"LSP request $id completed successfully")
            promise.success(result)
          case Left(_) =>
            cursor.downField("error").as[Json] match {
              case Right(error) => 
                logger.severe(s"LSP request $id failed with error: $error")
                promise.failure(new RuntimeException(s"LSP error: $error"))
              case Left(_) => 
                logger.severe(s"LSP request $id failed - invalid response format")
                promise.failure(new RuntimeException("Invalid LSP response"))
            }
        }
        
      case _ =>
        // This is a notification or request from server
        cursor.downField("method").as[String] match {
          case Right(method) =>
            val params = cursor.downField("params").as[Json].getOrElse(Json.Null)
            
            messageHandlers.get(method) match {
              case handler if handler != null =>
                try {
                  val result = handler(params)
                  
                  // If this is a request (has id), send response
                  cursor.downField("id").as[Int] match {
                    case Right(requestId) =>
                      result match {
                        case Some(responseData) => sendResponse(requestId, responseData)
                        case None => sendResponse(requestId, Json.Null)
                      }
                    case Left(_) => // This is a notification, no response needed
                  }
                } catch {
                  case e: Exception =>
                    logger.severe(s"Error handling $method: ${e.getMessage}")
                    cursor.downField("id").as[Int] match {
                      case Right(requestId) => sendErrorResponse(requestId, e.getMessage)
                      case Left(_) => // Notification, can't send error response
                    }
                }
                
              case null =>
                logger.warning(s"Unhandled LSP method: $method")
                logger.info(s"Unhandled method params: $params")
                // Check if this is a request (has id) that needs a response
                cursor.downField("id").as[Int] match {
                  case Right(requestId) => 
                    logger.warning(s"Unhandled method $method is a request (id: $requestId) - sending empty response")
                    sendResponse(requestId, Json.Null)
                  case Left(_) => 
                    logger.info(s"Unhandled method $method is a notification - no response needed")
                }
            }
            
          case Left(_) =>
            logger.warning(s"Message without method: $message")
        }
    }
  }

  private def sendMessage(message: Json): Unit = {
    val messageStr = message.noSpaces
    val messageBytes = messageStr.getBytes(StandardCharsets.UTF_8)
    val header = s"Content-Length: ${messageBytes.length}\r\n\r\n"
    
    try {
      stdin.print(header)
      stdin.print(messageStr)
      stdin.flush()
    } catch {
      case e: Exception =>
        logger.severe(s"Failed to send message: ${e.getMessage}")
    }
  }

  private def sendResponse(messageId: Int, result: Json): Unit = {
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> messageId.asJson,
      "result" -> result
    )
    sendMessage(response)
  }

  private def sendErrorResponse(messageId: Int, error: String): Unit = {
    val response = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> messageId.asJson,
      "error" -> Json.obj(
        "code" -> (-32603).asJson, // Internal error
        "message" -> error.asJson
      )
    )
    sendMessage(response)
  }

  def sendRequest(method: String, params: Option[Json] = None): Future[Json] = {
    val id = requestId.incrementAndGet()
    val promise = Promise[Json]()
    
    logger.info(s"Sending LSP request: $method (id: $id)")
    pendingRequests.put(id, promise)
    
    val request = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "id" -> id.asJson,
      "method" -> method.asJson
    ) deepMerge (params match {
      case Some(p) => Json.obj("params" -> p)
      case None => Json.obj()
    })
    
    logger.info(s"LSP request JSON: ${request.noSpaces}")
    sendMessage(request)
    logger.info(s"LSP request sent: $method (id: $id)")
    promise.future
  }

  def sendNotification(method: String, params: Option[Json] = None): Unit = {
    val notification = Json.obj(
      "jsonrpc" -> "2.0".asJson,
      "method" -> method.asJson
    ) deepMerge (params match {
      case Some(p) => Json.obj("params" -> p)
      case None => Json.obj()
    })
    
    sendMessage(notification)
  }

  // Message handlers (implementing minimal MetalsLanguageClient interface)

  private def handleShowMessage(params: Json): Option[Json] = {
    val cursor = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message = cursor.downField("message").as[String].getOrElse("")
    
    val typeName = messageType match {
      case 1 => "ERROR"
      case 2 => "WARN" 
      case 3 => "INFO"
      case 4 => "LOG"
      case _ => "UNKNOWN"
    }
    
    logger.info(s"[$typeName] $message")
    None
  }

  private def handleShowMessageRequest(params: Json): Option[Json] = {
    val cursor = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message = cursor.downField("message").as[String].getOrElse("")
    val actions = cursor.downField("actions").as[List[Json]].getOrElse(Nil)
    
    val typeName = messageType match {
      case 1 => "ERROR"
      case 2 => "WARN"
      case 3 => "INFO" 
      case 4 => "LOG"
      case _ => "UNKNOWN"
    }
    
    logger.info(s"[$typeName] $message")
    
    // Auto-select the first action for headless operation
    if (actions.nonEmpty) {
      val selectedAction = actions.head
      val actionTitle = selectedAction.hcursor.downField("title").as[String].getOrElse("Unknown")
      logger.info(s"Auto-selecting action: $actionTitle")
      Some(selectedAction)
    } else {
      Some(Json.Null)
    }
  }

  private def handleLogMessage(params: Json): Option[Json] = {
    val cursor = params.hcursor
    val messageType = cursor.downField("type").as[Int].getOrElse(1)
    val message = cursor.downField("message").as[String].getOrElse("")
    
    if (messageType <= 2) { // Error or Warning
      logger.warning(s"Metals: $message")
    } else {
      logger.info(s"Metals: $message")
    }
    None
  }

  private def handlePublishDiagnostics(params: Json): Option[Json] = {
    val cursor = params.hcursor
    val uri = cursor.downField("uri").as[String].getOrElse("")
    val diagnostics = cursor.downField("diagnostics").as[List[Json]].getOrElse(Nil)
    
    if (diagnostics.nonEmpty) {
      logger.info(s"Diagnostics for $uri: ${diagnostics.length} issues")
    }
    None
  }

  private def handleApplyEdit(params: Json): Option[Json] = {
    // Just return success - we're not actually applying edits
    Some(Json.obj("applied" -> true.asJson))
  }

  private def handleMetalsStatus(params: Json): Option[Json] = {
    val text = params.hcursor.downField("text").as[String].getOrElse("")
    if (text.nonEmpty) {
      logger.info(s"Metals status: $text")
    }
    None
  }

  private def handleExecuteClientCommand(params: Json): Option[Json] = {
    val command = params.hcursor.downField("command").as[String].getOrElse("")
    logger.info(s"Client command: $command") 
    None
  }

  private def handleRegisterCapability(params: Json): Option[Json] = {
    // Just return success
    Some(Json.Null)
  }

  private def handleUnregisterCapability(params: Json): Option[Json] = {
    // Just return success
    Some(Json.Null)
  }

  private def handleProgressCreate(params: Json): Option[Json] = {
    // Just return success
    Some(Json.Null)
  }

  private def handleProgress(params: Json): Option[Json] = {
    // Ignore progress notifications
    None
  }

  private def handleConfiguration(params: Json): Option[Json] = {
    val cursor = params.hcursor
    val items = cursor.downField("items").as[List[Json]].getOrElse(Nil)
    
    // Return configuration for metals
    val configs = items.map { item =>
      val section = item.hcursor.downField("section").as[String].getOrElse("")
      if (section == "metals") {
        Json.obj(
          "startMcpServer" -> true.asJson,
          "isHttpEnabled" -> true.asJson, // Required for MCP server
          "statusBarProvider" -> "off".asJson,
          "inputBoxProvider" -> false.asJson,
          "quickPickProvider" -> false.asJson,
          "executeClientCommandProvider" -> false.asJson,
          "isExitOnShutdown" -> true.asJson
        )
      } else {
        Json.obj()
      }
    }
    
    Some(configs.asJson)
  }

  def shutdown(): Future[Json] = {
    shutdownRequested = true
    
    // Send shutdown request
    sendRequest("shutdown").andThen {
      case Success(_) =>
        // Send exit notification
        sendNotification("exit")
        
        // Close streams and terminate process
        Try {
          stdin.close()
          stdout.close()
        }
        
        readerExecutor.shutdown()
        
        if (process.isAlive) {
          process.destroy()
          // Give it a moment for graceful shutdown
          Thread.sleep(1000)
          if (process.isAlive) {
            process.destroyForcibly()
          }
        }
        
      case Failure(e) =>
        logger.warning(s"Shutdown request failed: ${e.getMessage}")
        // Force shutdown anyway
        Try {
          stdin.close()
          stdout.close()
        }
        
        readerExecutor.shutdown()
        process.destroyForcibly()
    }
  }
}