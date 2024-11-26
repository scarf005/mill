package mill.main.client

import java.io.{InputStream, OutputStream}
import java.util.function.{BooleanSupplier, Supplier}

class InputPumper(
    src0: Supplier[InputStream],
    dest0: Supplier[OutputStream],
    checkAvailable: Boolean,
    runningCheck: BooleanSupplier = () => true
) extends Runnable {

  private var running = true

  def run(): Unit = {
    val src = src0.get()
    val dest = dest0.get()

    val buffer = new Array[Byte](1024)
    try {
      while (running) {
        if (!runningCheck.getAsBoolean) {
          running = false
        } else if (checkAvailable && src.available() == 0) Thread.sleep(2)
        else {
          val n = try {
            src.read(buffer)
          } catch {
            case _: Exception => -1
          }
          if (n == -1) {
            running = false
          } else {
            try {
              dest.write(buffer, 0, n)
              dest.flush()
            } catch {
              case _: java.io.IOException => running = false
            }
          }
        }
      }
    } catch {
      case e: Exception => throw new RuntimeException(e)
    }
  }
}
