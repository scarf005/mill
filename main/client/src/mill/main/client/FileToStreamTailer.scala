package mill.main.client

import java.io._
import scala.util.Using

class FileToStreamTailer(file: File, stream: PrintStream, intervalMsec: Int)
    extends Thread("Tail")
    with AutoCloseable {

  // if true, we won't read the whole file, but only new lines
  private var ignoreHead = true

  @volatile private var keepReading = true
  @volatile private var flush = false

  setDaemon(true)

  override def run(): Unit = {
    if (isInterrupted) {
      keepReading = false
    }
    var readerOpt: Option[BufferedReader] = None
    try {
      while (keepReading || flush) {
        flush = false
        try {
          // Init reader, if not already done
          if (readerOpt.isEmpty) {
            try {
              readerOpt = Some(new BufferedReader(new FileReader(file)))
            } catch {
              case _: FileNotFoundException =>
                // nothing to ignore if file is initially missing
                ignoreHead = false
            }
          }

          readerOpt.foreach { r =>
            // read lines
            try {
              Iterator.continually(r.readLine())
                .takeWhile(_ != null)
                .foreach { line =>
                  if (!ignoreHead) {
                    stream.println(line)
                  }
                }
              // we ignored once
              ignoreHead = false
            } catch {
              case _: IOException =>
              // could not read line or file vanished
            }
          }
        } finally {
          if (keepReading) {
            // wait
            try {
              Thread.sleep(intervalMsec)
            } catch {
              case _: InterruptedException =>
              // can't handle anyway
            }
          }
        }
      }
    } finally {
      readerOpt.foreach { r =>
        try {
          r.close()
        } catch {
          case _: IOException =>
          // could not close but also can't do anything about it
        }
      }
    }
  }

  override def interrupt(): Unit = {
    keepReading = false
    super.interrupt()
  }

  /**
   * Force a next read, even if we interrupt the thread.
   */
  def flush(): Unit = {
    this.flush = true
  }

  override def close(): Unit = {
    flush()
    interrupt()
  }
}
