package mill.main.client

import utest._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, OutputStream}
import java.nio.file.{Files, Paths}
import scala.util.Random

object ClientTests extends TestSuite {
  val tests = Tests {
    test("readWriteInt") {
      val examples = Array(
        0,
        1,
        126,
        127,
        128,
        254,
        255,
        256,
        1024,
        99999,
        1234567,
        Integer.MAX_VALUE,
        Integer.MAX_VALUE / 2,
        Integer.MIN_VALUE
      )
      for {
        example0 <- examples
        example <- Array(-example0, example0)
      } {
        val o = new ByteArrayOutputStream()
        Util.writeInt(o, example)
        val i = new ByteArrayInputStream(o.toByteArray)
        val s = Util.readInt(i)
        assert(example == s)
        assert(i.available() == 0)
      }
    }

    test("readWriteString") {
      val examples = Array(
        "",
        "hello",
        "i am cow",
        "i am cow\nhear me moo\ni weight twice as much as you",
        "我是一个叉烧包"
      )
      for (example <- examples) {
        checkStringRoundTrip(example)
      }
    }

    test("readWriteBigString") {
      val lengths = Array(0, 1, 126, 127, 128, 254, 255, 256, 1024, 99999, 1234567)
      for (length <- lengths) {
        val bigChars = Array.fill(length)('X')
        checkStringRoundTrip(new String(bigChars))
      }
    }

    test("tinyProxyInputOutputStream") {
      proxyInputOutputStreams(readSamples("/bandung.jpg").take(30), Array.empty, 10)
    }

    test("leftProxyInputOutputStream") {
      proxyInputOutputStreams(
        readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
        Array.empty,
        2950
      )
    }

    test("rightProxyInputOutputStream") {
      proxyInputOutputStreams(
        Array.empty,
        readSamples("/bandung.jpg", "/akanon.mid", "/gettysburg.txt", "/pip.tar.gz"),
        3000
      )
    }

    test("mixedProxyInputOutputStream") {
      proxyInputOutputStreams(
        readSamples("/bandung.jpg", "/gettysburg.txt"),
        readSamples("/akanon.mid", "/pip.tar.gz"),
        3050
      )
    }
  }

  def checkStringRoundTrip(example: String): Unit = {
    val o = new ByteArrayOutputStream()
    Util.writeString(o, example)
    val i = new ByteArrayInputStream(o.toByteArray)
    val s = Util.readString(i)
    assert(example == s)
    assert(i.available() == 0)
  }

  def readSamples(samples: String*): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    for (sample <- samples) {
      val bytes = Files.readAllBytes(
        Paths.get(getClass.getResource(sample).toURI)
      )
      out.write(bytes)
    }
    out.toByteArray
  }

  def proxyInputOutputStreams(samples1: Array[Byte], samples2: Array[Byte], chunkMax: Int): Unit = {
    val pipe = new ByteArrayOutputStream()
    val src1: OutputStream = new ProxyStream.Output(pipe, ProxyStream.OUT)
    val src2: OutputStream = new ProxyStream.Output(pipe, ProxyStream.ERR)

    val random = new Random(31337)

    var i1 = 0
    var i2 = 0
    while (i1 < samples1.length || i2 < samples2.length) {
      val chunk = random.nextInt(chunkMax)
      if (random.nextBoolean() && i1 < samples1.length) {
        src1.write(samples1, i1, math.min(samples1.length - i1, chunk))
        src1.flush()
        i1 += chunk
      } else if (i2 < samples2.length) {
        src2.write(samples2, i2, math.min(samples2.length - i2, chunk))
        src2.flush()
        i2 += chunk
      }
    }

    val bytes = pipe.toByteArray

    val dest1 = new ByteArrayOutputStream()
    val dest2 = new ByteArrayOutputStream()
    val pumper = new ProxyStream.Pumper(new ByteArrayInputStream(bytes), dest1, dest2)
    pumper.run()
    assert(java.util.Arrays.equals(samples1, dest1.toByteArray))
    assert(java.util.Arrays.equals(samples2, dest2.toByteArray))
  }
}
