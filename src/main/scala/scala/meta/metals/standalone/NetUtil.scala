package scala.meta.metals.standalone

import kyo.*

import java.net.{BindException, InetAddress, InetSocketAddress, ServerSocket}

object NetUtil:
  val DefaultMcpPort: Int = 60013

  def configuredMcpPort(): Int =
    sys.env.get("METALS_MCP_PORT").flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(DefaultMcpPort)

  def isPortAvailable(port: Int): Boolean < Sync =
    Sync.defer {
      var socket: ServerSocket | Null = null
      try
        socket = new ServerSocket()
        socket.setReuseAddress(true)
        // Bind specifically to localhost to mirror how we use the port
        socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress, port))
        true
      catch
        case _: BindException => false
      finally
        socket match
          case s: ServerSocket =>
            try s.close() catch case _: Throwable => ()
          case null => ()
    }
