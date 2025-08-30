package scala.meta.metals.standalone

import io.circe.*
import io.circe.syntax.*
import java.nio.file.Path
import kyo.*
import kyo.Log

/** Kyo-based Metals language client orchestrator.
  * Minimal port of MetalsClient to work with LspClientK.
  */
class MetalsClient(projectPath: Path, lspClient: LspClient)(using Frame):

  @volatile private var initialized = false

  def initialize(): Boolean < (Async & Sync) =
    if initialized then Sync.defer(true)
    else
      Log.info("Initializing Metals language server...")
        .andThen {
          val initParams = createInitializeParams()
          lspClient
            .sendRequest("initialize", Some(initParams))
            .flatMap { result =>
              val hasCapabilities = result.hcursor.downField("capabilities").succeeded
              if hasCapabilities then
                Log.info("Metals language server initialized successfully").andThen {
                  lspClient.sendNotification("initialized", Some(Json.obj()))
                }.andThen {
                  Sync.defer(Thread.sleep(500)) // allow Metals to process initialized
                }.andThen {
                  Log.info("Configuring Metals...")
                }.andThen {
                  configureMetals()
                }.andThen {
                  initialized = true
                  Sync.defer(true)
                }
              else
                Log.error("Failed to initialize Metals language server - no capabilities in response")
                  .andThen(Log.error(s"Response was: $result"))
                  .andThen(Sync.defer(false))
            }
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
      "isHttpEnabled"                -> true.asJson,
      "quickPickProvider"            -> false.asJson,
      "renameProvider"               -> false.asJson,
      "statusBarProvider"            -> "off".asJson,
      "treeViewProvider"             -> false.asJson,
      "bloopEmbeddedServer"          -> false.asJson,
      "automaticImportBuild"         -> "off".asJson,
      "askToReconnect"               -> false.asJson
    )

  private def configureMetals(): Unit < Sync =
    val configParams = Json.obj(
      "settings" -> Json.obj(
        "metals" -> Json.obj(
          "startMcpServer" -> true.asJson
        )
      )
    )
    lspClient.sendNotification("workspace/didChangeConfiguration", Some(configParams))

  def shutdown(): Unit < (Async & Sync) =
    lspClient.shutdown().map(_ => ())
