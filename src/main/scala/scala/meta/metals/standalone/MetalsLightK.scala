package scala.meta.metals.standalone

import kyo.*
import kyo.Log

import java.nio.file.Path
import scala.concurrent.ExecutionContext

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Ports the control flow of MetalsLight to Kyo effects, removing the need to use
  * Sync.Unsafe for MetalsLauncherK. It reuses the existing Future-based LSP/Metals
  * clients by bridging with Async.fromFuture.
  */
class MetalsLightK(projectPath: Path, verbose: Boolean):
  private val launcher                               = new MetalsLauncherK(projectPath)
  private var lspClientK: Option[LspClientK]         = None
  private var metalsClientK: Option[MetalsClientK]   = None
  implicit private val ec: ExecutionContext      = ExecutionContext.global

  private def requireSome[A](opt: Option[A], msg: String)(using Frame): A < (Sync & Abort[Throwable]) =
    opt match
      case Some(v) => Sync.defer(v)
      case None    => Abort.fail(new RuntimeException(msg))

  def run()(using Frame): Unit < (Async & Sync & Scope & Abort[Throwable]) =
    Scope.ensure {
      // Best-effort cleanup in reverse order
      Log.info("🔄 Shutting down components...")
        .andThen(metalsClientK match
          case Some(mc) => mc.shutdown()
          case None     => Sync.defer(())
        )
        .andThen(lspClientK match
          case Some(c)  => c.shutdown()
          case None     => Sync.defer(())
        )
        .andThen(launcher.shutdown())
        .andThen(Log.info("👋 Goodbye!"))
    }.andThen {
      Log.info("🚀 Starting Metals standalone MCP client...")
        .andThen(launcher.validateProject().flatMap { isValid =>
          if isValid then Sync.defer(())
          else Log.error("❌ Project validation failed").andThen(Abort.fail(new RuntimeException("validation failed")))
        })
        .andThen(Log.info("📦 Launching Metals language server..."))
        .andThen(launcher.launchMetals().flatMap(opt => requireSome(opt, "❌ Failed to launch Metals")))
        .flatMap { proc =>
          val jproc = new KyoProcessAdapter(proc)
          val lspK  = new LspClientK(jproc)
          lspClientK = Some(lspK)

          lspK
            .start()
            .andThen(Log.info("🔗 Connected to Metals LSP server"))
            .andThen {
              val metalsK = new MetalsClientK(projectPath, lspK)
              metalsClientK = Some(metalsK)
              metalsK
                .initialize()
                .flatMap { initialized =>
                  if initialized then Log.info("✅ Metals language server initialized")
                  else Log.error("❌ Failed to initialize Metals").andThen(Abort.fail(new RuntimeException("init failed")))
                }
                .andThen {
                  val monitorK = new McpMonitorK(projectPath)
                  Log.info("⏳ Waiting for MCP server to start...")
                    .andThen(monitorK.waitForMcpServer())
                    .flatMap(opt => requireSome(opt, "❌ MCP server failed to start"))
                    .flatMap { url =>
                      monitorK.printConnectionInfo(url)
                        .andThen(monitorK.monitorMcpHealth(url).map(_ => ()))
                    }
                }
            }
        }
    }
