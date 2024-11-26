package mill.main.client

import java.io.{IOException, InputStream, OutputStream}

/**
 * Logic to capture a pair of streams (typically stdout and stderr), combining
 * them into a single stream, and splitting it back into two streams later while
 * preserving ordering. This is useful for capturing stderr and stdout and forwarding
 * them to a terminal while strictly preserving the ordering, i.e. users won't see
 * exception stack traces and printlns arriving jumbled up and impossible to debug
 *
 * This works by converting writes from either of the two streams into packets of
 * the form:
 *
 *  1 byte         n bytes
 * | header |         body |
 *
 * Where header is a single byte of the form:
 *
 * - header more than 0 indicating that this packet is for the `OUT` stream
 * - header less than 0 indicating that this packet is for the `ERR` stream
 * - abs(header) indicating the length of the packet body, in bytes
 * - header == 0 indicating the end of the stream
 *
 * Writes to either of the two `Output`s are synchronized on the shared
 * `destination` stream, ensuring that they always arrive complete and without
 * interleaving. On the other side, a `Pumper` reads from the combined
 * stream, forwards each packet to its respective destination stream, or terminates
 * when it hits a packet with `header == 0`
 */
object ProxyStream {
  val OUT = 1
  val ERR = -1
  val END = 0

  def sendEnd(out: OutputStream): Unit = out.synchronized {
    out.write(ProxyStream.END)
    out.flush()
  }

  class Output(destination: OutputStream, key: Int) extends OutputStream {
    override def write(b: Int): Unit = destination.synchronized {
      destination.write(key)
      destination.write(b)
    }

    override def write(b: Array[Byte]): Unit = {
      if (b.length > 0) {
        destination.synchronized {
          write(b, 0, b.length)
        }
      }
    }

    override def write(b: Array[Byte], off: Int, len: Int): Unit = {
      destination.synchronized {
        var i = 0
        while (i < len && i + off < b.length) {
          val chunkLength = math.min(len - i, 127)
          if (chunkLength > 0) {
            destination.write(chunkLength * key)
            destination.write(b, off + i, math.min(b.length - off - i, chunkLength))
            i += chunkLength
          }
        }
      }
    }

    override def flush(): Unit = destination.synchronized {
      destination.flush()
    }

    override def close(): Unit = destination.synchronized {
      destination.close()
    }
  }

  class Pumper(
      src: InputStream,
      destOut: OutputStream,
      destErr: OutputStream,
      synchronizer: AnyRef = new AnyRef
  ) extends Runnable {

    def preRead(src: InputStream): Unit = {}

    def preWrite(buffer: Array[Byte], length: Int): Unit = {}

    def run(): Unit = {
      val buffer = new Array[Byte](1024)
      while (true) {
        try {
          this.preRead(src)
          val header = src.read()
          // -1 means socket was closed, 0 means a ProxyStream.END was sent. Note
          // that only header values > 0 represent actual data to read:
          // - sign((byte)header) represents which stream the data should be sent to
          // - abs((byte)header) represents the length of the data to read and send
          if (header == -1 || header == 0) {
            return
          } else {
            val stream = if (header.toByte > 0) 1 else -1
            val quantity0 = header.toByte
            val quantity = math.abs(quantity0)
            var offset = 0
            var delta = -1
            while (offset < quantity) {
              this.preRead(src)
              delta = src.read(buffer, offset, quantity - offset)
              if (delta == -1) {
                return
              } else {
                offset += delta
              }
            }

            if (delta != -1) {
              synchronizer.synchronized {
                this.preWrite(buffer, offset)
                stream match {
                  case ProxyStream.OUT => destOut.write(buffer, 0, offset)
                  case ProxyStream.ERR => destErr.write(buffer, 0, offset)
                }
              }
            }
          }
        } catch {
          case _: IOException =>
            // This happens when the upstream pipe was closed
            return
        }
      }
    }

    def flush(): Unit = synchronizer.synchronized {
      try {
        destOut.flush()
        destErr.flush()
      } catch {
        case _: IOException => // Ignore
      }
    }
  }
}
