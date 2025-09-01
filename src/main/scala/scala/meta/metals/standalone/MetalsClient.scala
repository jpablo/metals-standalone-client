package scala.meta.metals.standalone

import io.circe.*
import io.circe.syntax.*
import kyo.*

import java.nio.file.Path
import java.util.logging.Logger
import scala.concurrent.{ExecutionContext, Future}

/** Minimal Metals Language Client that implements the essential LSP protocol to start Metals and enable the MCP server.
  */
class MetalsClient(
    projectPath: Path,
    lspClient:   LspClient,
    initTimeout: kyo.Duration = 1.minutes
)(using ExecutionContext):
  private val logger = Logger.getLogger(classOf[MetalsClient].getName)

  @volatile private var initialized = false

  def initialize() =
    if initialized then
      Log.info("Already initialized, returning success").andThen {
        Sync.defer(true)
      }
    else
      for
        _ <- Log.info("Initializing Metals language server...")
        initParams = createInitializeParams()
        _ <- Log.debug("Created initialization parameters")
        _ <- Log.debug("Sending initialize request to Metals...")
        initializeFuture = Async.fromFuture(lspClient.sendRequest("initialize", Some(initParams)))
        result <- Async.timeout(initTimeout)(initializeFuture)
        _      <- Log.debug("Received initialize response from Metals")
        hasCapabilities = result.hcursor.downField("capabilities").succeeded

        ret <-
          if hasCapabilities then
            for
              _ <- Log.info("Metals language server initialized successfully")
              _ <- Log.debug("Sending initialized notification...")
              _ = lspClient.sendNotification("initialized", Some(Json.obj()))

              // Small delay to let Metals process the initialized notification
              _ <- Async.sleep(500.millis)
              _ <- Log.debug("Configuring Metals...")
              _ = configureMetals()
              _ = initialized = true
              _ <- Log.debug("Initialization complete!")
            yield true
          else
            for
              _ <- Log.error("Failed to initialize Metals language server - no capabilities in response")
              _ <- Log.debug(s"Response was: $result")
            yield false
      yield ret

  private def createInitializeParams(): Json =
    Json.obj(
      "processId" -> Json.Null,
      "clientInfo" -> Json.obj(
        "name"    -> "metals-standalone-client".asJson,
        "version" -> "0.1.0".asJson
      ),
      "rootUri" -> projectPath.toUri.toString.asJson,
      "workspaceFolders" -> Json.arr(
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
      "workspace" -> Json.obj(
        "applyEdit"        -> true.asJson,
        "configuration"    -> true.asJson,
        "workspaceFolders" -> true.asJson,
        "didChangeConfiguration" -> Json.obj(
          "dynamicRegistration" -> false.asJson
        )
      ),
      "textDocument" -> Json.obj(
        "synchronization" -> Json.obj(
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
      "window" -> Json.obj(
        "showMessage" -> Json.obj(
          "messageActionItem" -> Json.obj(
            "additionalPropertiesSupport" -> false.asJson
          )
        ),
        "showDocument" -> Json.obj(
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
      "compilerOptions" -> Json.obj(
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
      "bloopEmbeddedServer"  -> false.asJson,
      "automaticImportBuild" -> "off".asJson,
      "askToReconnect"       -> false.asJson
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
