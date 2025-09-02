package scala.meta.metals.standalone

import io.circe.*
import io.circe.syntax.*
import kyo.*

import java.nio.file.Path

/** Minimal Metals Language Client that implements the essential LSP protocol to start Metals and enable the MCP server.
  */
class MetalsClient(
    projectPath: Path,
    lspClient:   LspClient,
    initTimeout: Duration = 1.minutes
):
  private val initialized = AtomicBoolean.init(false)

  def initialize(): Boolean < (Async & Abort[Throwable]) = direct {
    val isInitialized = initialized.map(_.get).now
    if isInitialized then
      Log.info("Already initialized, returning success").now
      true
    else
      Log.info("Initializing Metals language server...").now
      val initParams = createInitializeParams()
      Log.debug("Sending initialize request to Metals...").now
      val result: Json = Async.timeout(initTimeout)(lspClient.sendRequest("initialize", Some(initParams))).now
      val hasCapabilities = result.hcursor.downField("capabilities").succeeded
      if hasCapabilities then
        Log.info("Metals language server initialized successfully").now
        lspClient.sendNotification("initialized", Some(Json.obj())).now
        Async.sleep(500.millis).now
        Log.debug("Configuring Metals...").now
        configureMetals().now
        initialized.map(_.set(true)).now
        Log.debug("Initialization complete!").now
        true
      else
        Log.error("Failed to initialize Metals language server - no capabilities in response").now
        false
  }

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

  private def configureMetals(): Unit < Sync =
    val configParams = Json.obj(
      "settings" -> Json.obj(
        "metals" -> Json.obj(
          "startMcpServer" -> true.asJson
        )
      )
    )
    direct {
      val port = NetUtil.configuredMcpPort()
      val ok   = NetUtil.isPortAvailable(port).now
      if !ok then
        Log.error(
          s"Metals MCP port appears to be in use (port $port). The MCP server may fail to start with BindException.\n" +
            s"To identify the process: lsof -nP -iTCP:$port -sTCP:LISTEN  (macOS/Linux)\n" +
            s"Or: ss -lptn 'sport = :$port' (Linux)."
        ).now

      Log.debug("Configuring Metals to enable MCP server...").now
      lspClient.sendNotification("workspace/didChangeConfiguration", Some(configParams)).now
    }

  def shutdown(): Unit < (Async & Abort[Throwable]) = direct {
    Log.info("Shutting down Metals client...").now
    lspClient.shutdown().now
  }
