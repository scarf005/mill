package mill.main.client

import utest._
import org.apache.commons.io.output.TeeOutputStream

import java.io._

object ProxyStreamTests extends TestSuite {
  val tests = Tests {
    test("proxyStreamFuzzing") {
      // Test writes of sizes around 1, around 127, around 255, and much larger. These
      // are likely sizes to have bugs since we write data in chunks of size 127
      val interestingLengths = Array(
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 20, 30, 40, 50, 100, 126, 127, 128, 129, 130, 253, 254, 255,
        256, 257, 1000, 2000, 4000, 8000
      )
      val interestingBytes = Array[Byte](
        -1, -127, -126, -120, -100, -80, -60, -40, -20, -10, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 10,
        20, 40, 60, 80, 100, 120, 125, 126, 127
      ).map(_.toByte)

      for (n <- interestingLengths) {
        println(s"ProxyStreamTests fuzzing length $n")
        for (r <- 1 until interestingBytes.length + 1) {
          val outData = new Array[Byte](n)
          val errData = new Array[Byte](n)
          for (j <- 0 until n) {
            // fill test data blobs with arbitrary bytes from `interestingBytes`, negating
            // the bytes we use for `errData` so we can distinguish it from `outData`
            //
            // offset the start byte we use by `r`, so we exercise writing blobs
            // that start with every value listed in `interestingBytes`
            outData(j) = interestingBytes((j + r) % interestingBytes.length)
            errData(j) = (-interestingBytes((j + r) % interestingBytes.length)).toByte
          }

          // Run all tests both with the format `ProxyStream.END` packet
          // being sent as well as when the stream is unceremoniously closed
          test0(outData, errData, r, gracefulEnd = false)
          test0(outData, errData, r, gracefulEnd = true)
        }
      }
    }
  }

  def test0(outData: Array[Byte], errData: Array[Byte], repeats: Int, gracefulEnd: Boolean): Unit = {
    val pipedOutputStream = new PipedOutputStream()
    val pipedInputStream = new PipedInputStream(1000000)

    pipedInputStream.connect(pipedOutputStream)

    val srcOut = new ProxyStream.Output(pipedOutputStream, ProxyStream.OUT)
    val srcErr = new ProxyStream.Output(pipedOutputStream, ProxyStream.ERR)

    // Capture both the destOut/destErr from the pumper, as well as the destCombined
    // to ensure the individual streams contain the right data and combined stream
    // is in the right order
    val destOut = new ByteArrayOutputStream()
    val destErr = new ByteArrayOutputStream()
    val destCombined = new ByteArrayOutputStream()
    val pumper = new ProxyStream.Pumper(
      pipedInputStream,
      new TeeOutputStream(destOut, destCombined),
      new TeeOutputStream(destErr, destCombined)
    )

    val writerThread = new Thread(() => {
      try {
        for (_ <- 0 until repeats) {
          srcOut.write(outData)
          srcErr.write(errData)
        }

        if (gracefulEnd) ProxyStream.sendEnd(pipedOutputStream)
        else {
          pipedOutputStream.close()
        }
      } catch {
        case e: Exception => e.printStackTrace()
      }
    })
    writerThread.start()

    val pumperThread = new Thread(pumper)
    pumperThread.start()
    pumperThread.join()

    // Check that the individual `destOut` and `destErr` contain the correct bytes
    assert(java.util.Arrays.equals(repeatArray(outData, repeats), destOut.toByteArray))
    assert(java.util.Arrays.equals(repeatArray(errData, repeats), destErr.toByteArray))

    // Check that the combined `destCombined` contains the correct bytes in the correct order
    val combinedData = new Array[Byte](outData.length + errData.length)

    System.arraycopy(outData, 0, combinedData, 0, outData.length)
    System.arraycopy(errData, 0, combinedData, outData.length, errData.length)

    val expectedCombined = repeatArray(combinedData, repeats)
    assert(java.util.Arrays.equals(expectedCombined, destCombined.toByteArray))
  }

  def repeatArray(arr: Array[Byte], n: Int): Array[Byte] = {
    val out = new Array[Byte](arr.length * n)
    for (i <- 0 until n) {
      System.arraycopy(arr, 0, out, i * arr.length, arr.length)
    }
    out
  }
}
