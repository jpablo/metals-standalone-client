package scala.meta.metals.standalone

import kyo.Process as KyoProcess
import kyo.Sync
import kyo.AllowUnsafe.embrace.danger

import java.io.{InputStream, OutputStream}

/** Adapts a kyo.Process to a java.lang.Process so existing code that depends on
  * java.lang.Process (e.g., LspClientK) can continue to work without changes.
  */
class KyoProcessAdapter(private val kp: KyoProcess) extends java.lang.Process:
  override def getOutputStream: OutputStream = kp.stdin
  override def getInputStream: InputStream   = kp.stdout
  override def getErrorStream: InputStream   = kp.stderr

  override def waitFor(): Int =
    Sync.Unsafe.evalOrThrow(kp.waitFor)

  override def waitFor(timeout: Long, unit: java.util.concurrent.TimeUnit): Boolean =
    Sync.Unsafe.evalOrThrow(kp.waitFor(timeout, unit))

  override def exitValue(): Int =
    Sync.Unsafe.evalOrThrow(kp.exitValue)

  override def destroy(): Unit =
    Sync.Unsafe.evalOrThrow(kp.destroy)

  override def destroyForcibly(): java.lang.Process =
    Sync.Unsafe.evalOrThrow(kp.destroyForcibly)

  override def isAlive(): Boolean =
    Sync.Unsafe.evalOrThrow(kp.isAlive)
