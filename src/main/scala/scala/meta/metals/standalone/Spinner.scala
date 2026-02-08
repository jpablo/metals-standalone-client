package scala.meta.metals.standalone

/** Animated braille spinner for terminal feedback during startup.
  *
  * Prints an animated spinner with a message on a single line, overwriting in-place using `\r`.
  * Runs in a daemon thread so it doesn't prevent JVM exit.
  */
class Spinner:
  private val frames = Array("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
  @volatile private var message  = ""
  @volatile private var running  = false
  private var thread: Option[Thread] = None

  def start(msg: String): Unit =
    message = msg
    running = true
    val t = Thread(() => {
      var i = 0
      while running do
        val line = s"\r${frames(i % frames.length)} $message"
        System.out.print(line)
        System.out.flush()
        i += 1
        try Thread.sleep(80)
        catch case _: InterruptedException => ()
    })
    t.setDaemon(true)
    t.setName("spinner")
    t.start()
    thread = Some(t)

  def update(msg: String): Unit =
    message = msg

  def stop(): Unit =
    running = false
    thread.foreach { t =>
      t.join(500)
    }
    thread = None
    // Clear the spinner line
    System.out.print("\r" + " " * 60 + "\r")
    System.out.flush()
