package scala.meta.metals.standalone

import kyo.*

import java.nio.file.Path
import java.util.logging.Logger
import scala.concurrent.ExecutionContext

/** Kyo-based runner for the standalone Metals MCP client.
  *
  * Ports the control flow of MetalsLight to Kyo effects, removing the need to use
  * Sync.Unsafe for MetalsLauncherK. It reuses the existing Future-based LSP/Metals
  * clients by bridging with Async.fromFuture.
  */
class MetalsLightK(projectPath: Path, verbose: Boolean):
  private val logger = Logger.getLogger(classOf[MetalsLightK].getName)

  private val launcher                           = new MetalsLauncherK(projectPath)
  private var lspClient: Option[LspClient]       = None
  private var metalsClient: Option[MetalsClient] = None
  implicit private val ec: ExecutionContext      = ExecutionContext.global

  private def requireSome[A](opt: Option[A], msg: String)(using Frame): A < (Sync & Abort[Throwable]) =
    opt match
      case Some(v) => Sync.defer(v)
      case None    => Abort.fail(new RuntimeException(msg))

  def run()(using Frame): Unit < (Async & Sync & Scope & Abort[Throwable]) =
    Scope.ensure {
      // Best-effort cleanup in reverse order
      Sync.defer(logger.info("🔄 Shutting down components...") )
        .andThen(metalsClient match
          case Some(mc) => Async.fromFuture(mc.shutdown()).map(_ => ())
          case None     => Sync.defer(())
        )
        .andThen(lspClient match
          case Some(c)  => Async.fromFuture(c.shutdown()).map(_ => ())
          case None     => Sync.defer(())
        )
        .andThen(launcher.shutdown())
        .andThen(Sync.defer(logger.info("👋 Goodbye!")))
    }.andThen {
      Sync.defer(logger.info("🚀 Starting Metals standalone MCP client..."))
        .andThen(launcher.validateProject().flatMap { isValid =>
          if isValid then Sync.defer(())
          else Abort.fail(new RuntimeException("❌ Project validation failed"))
        })
        .andThen(Sync.defer(logger.info("📦 Launching Metals language server...")))
        .andThen(launcher.launchMetals().flatMap(opt => requireSome(opt, "❌ Failed to launch Metals")))
        .flatMap { proc =>
          val jproc  = new KyoProcessAdapter(proc)
          val client = new LspClient(jproc)

          Sync.defer { lspClient = Some(client); () }
            .andThen(Async.fromFuture(client.start()))
            .andThen(Sync.defer(logger.info("🔗 Connected to Metals LSP server")))
            .andThen {
              val metals = new MetalsClient(projectPath, client)
              Sync.defer { metalsClient = Some(metals); () }
                .andThen(
                  Async.fromFuture(metals.initialize()).flatMap { initialized =>
                    if initialized then Sync.defer(logger.info("✅ Metals language server initialized"))
                    else Abort.fail(new RuntimeException("❌ Failed to initialize Metals"))
                  }
                )
                .andThen {
                  val monitor = new McpMonitor(projectPath)
                  Sync.defer(logger.info("⏳ Waiting for MCP server to start...") )
                    .andThen(Async.fromFuture(monitor.waitForMcpServer()))
                    .flatMap(opt => requireSome(opt, "❌ MCP server failed to start"))
                    .flatMap { url =>
                      Sync.defer(monitor.printConnectionInfo(url))
                        .andThen(Async.fromFuture(monitor.monitorMcpHealth(url)).map(_ => ()))
                    }
                }
            }
        }
    }
