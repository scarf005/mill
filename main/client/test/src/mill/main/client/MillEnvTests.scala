package mill.main.client

import org.junit.Assert.assertEquals
import org.junit.Test

import java.io.File

class MillEnvTests {
  @Test
  def readOptsFileLinesWithoutFInalNewline(): Unit = {
    val file = new File(
      getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI
    )
    val lines = Util.readOptsFileLines(file)
    assertEquals(
      lines,
      List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m")
    )
  }
}
