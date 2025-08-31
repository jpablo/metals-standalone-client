package scala.meta.metals.standalone

import io.circe.*
import io.circe.syntax.*

import java.nio.file.Path
import java.util.logging.Logger
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

/** Minimal Metals Language Client that implements the essential LSP protocol to start Metals and enable the MCP server.
  */
class MetalsClient(
    projectPath: Path,
    lspClient: LspClient,
    initTimeout: FiniteDuration = 5.minutes
)(using ExecutionContext):
  private val logger = Logger.getLogger(classOf[MetalsClient].getName)

  @volatile private var initialized = false

  def initialize(): Future[Boolean] =
    if initialized then
      logger.info("Already initialized, returning success")
      Future.successful(true)
    else
      logger.info("Initializing Metals language server...")

      val initParams = createInitializeParams()
      logger.fine("Created initialization parameters")

      logger.fine("Sending initialize request to Metals...")
      val initializeFuture = lspClient.sendRequest("initialize", Some(initParams))

      // Add a timeout to avoid hanging forever
      val timeoutFuture = scala.concurrent.Future {
        Thread.sleep(initTimeout.toMillis)
        throw new java.util.concurrent.TimeoutException(s"Initialize request timed out after ${initTimeout.toMinutes} minutes")
      }

      scala.concurrent.Future
        .firstCompletedOf(Seq(initializeFuture, timeoutFuture))
        .map { result =>
          logger.fine("Received initialize response from Metals")
          val hasCapabilities = result.hcursor.downField("capabilities").succeeded

          if hasCapabilities then
            logger.info("Metals language server initialized successfully")

            logger.fine("Sending initialized notification...")
            lspClient.sendNotification("initialized", Some(Json.obj()))

            // Small delay to let Metals process the initialized notification
            Thread.sleep(500)
            logger.fine("Configuring Metals...")
            configureMetals()

            initialized = true
            logger.fine("Initialization complete!")
            true
          else
            logger.severe("Failed to initialize Metals language server - no capabilities in response")
            logger.fine(s"Response was: $result")
            false
        }
        .recover { case e =>
          logger.severe(s"Metals initialization failed: ${e.getMessage}")
          e.printStackTrace()
          false
        }

  private def createInitializeParams(): Json =
    Json.obj(
      "processId"             -> Json.Null,
      "clientInfo"            -> Json.obj(
        "name"    -> "metals-standalone-client".asJson,
        "version" -> "0.1.0".asJson
      ),
      "rootUri"               -> projectPath.toUri.toString.asJson,
      "workspaceFolders"      -> Json.arr(
        Json.obj(
          "uri"  -> projectPath.toUri.toString.asJson,
          "name" -> projectPath.getFileName.toString.asJson
        )
      ),
      "capabilities"          -> createClientCapabilities(),
      "initializationOptions" -> createInitializationOptions()
    )

  private def createClientCapabilities(): Json =
    Json.obj(
      "workspace"    -> Json.obj(
        "applyEdit"              -> true.asJson,
        "configuration"          -> true.asJson,
        "workspaceFolders"       -> true.asJson,
        "didChangeConfiguration" -> Json.obj(
          "dynamicRegistration" -> false.asJson
        )
      ),
      "textDocument" -> Json.obj(
        "synchronization"    -> Json.obj(
          "dynamicRegistration" -> false.asJson,
          "willSave"            -> false.asJson,
          "willSaveWaitUntil"   -> false.asJson,
          "didSave"             -> false.asJson
        ),
        "publishDiagnostics" -> Json.obj(
          "relatedInformation"     -> false.asJson,
          "versionSupport"         -> false.asJson,
          "tagSupport"             -> Json.obj("valueSet" -> Json.arr()),
          "codeDescriptionSupport" -> false.asJson,
          "dataSupport"            -> false.asJson
        )
      ),
      "window"       -> Json.obj(
        "showMessage"      -> Json.obj(
          "messageActionItem" -> Json.obj(
            "additionalPropertiesSupport" -> false.asJson
          )
        ),
        "showDocument"     -> Json.obj(
          "support" -> false.asJson
        ),
        "workDoneProgress" -> true.asJson
      ),
      "experimental" -> Json.obj(
        "metals" -> Json.obj(
          "inputBoxProvider"             -> false.asJson,
          "quickPickProvider"            -> false.asJson,
          "executeClientCommandProvider" -> false.asJson,
          "statusBarProvider"            -> "off".asJson,
          "treeViewProvider"             -> false.asJson,
          "decorationProvider"           -> false.asJson
        )
      )
    )

  private def createInitializationOptions(): Json =
    Json.obj(
      "compilerOptions"              -> Json.obj(
        "completionCommand"                    -> "editor.action.triggerSuggest".asJson,
        "isCompletionItemDetailEnabled"        -> false.asJson,
        "isCompletionItemDocumentationEnabled" -> false.asJson,
        "overrideDefFormat"                    -> "ascii".asJson,
        "parameterHintsCommand"                -> "editor.action.triggerParameterHints".asJson
      ),
      "debuggingProvider"            -> false.asJson,
      "decorationProvider"           -> false.asJson,
      "executeClientCommandProvider" -> false.asJson,
      "inputBoxProvider"             -> false.asJson,
      "isExitOnShutdown"             -> true.asJson,
      "isHttpEnabled"                -> true.asJson, // Required for MCP server
      "quickPickProvider"            -> false.asJson,
      "renameProvider"               -> false.asJson,
      "statusBarProvider"            -> "off".asJson,
      "treeViewProvider"             -> false.asJson,
      // Disable BSP-related features that might be hanging
      "bloopEmbeddedServer"          -> false.asJson,
      "automaticImportBuild"         -> "off".asJson,
      "askToReconnect"               -> false.asJson
    )

  private def configureMetals(): Unit =
    logger.fine("Configuring Metals to enable MCP server...")

    val configParams = Json.obj(
      "settings" -> Json.obj(
        "metals" -> Json.obj(
          "startMcpServer" -> true.asJson
        )
      )
    )

    lspClient.sendNotification("workspace/didChangeConfiguration", Some(configParams))

  def shutdown(): Future[Unit] =
    logger.info("Shutting down Metals client...")
    lspClient.shutdown().map(_ => ())
