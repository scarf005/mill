package mill.main.client

import utest._

import java.io.File

object MillEnvTests extends TestSuite {
  val tests = Tests {
    test("readOptsFileLinesWithoutFInalNewline") {
      val file = new File(
        getClass.getClassLoader.getResource("file-wo-final-newline.txt").toURI
      )
      val lines = Util.readOptsFileLines(file)
      assert(
        lines == List("-DPROPERTY_PROPERLY_SET_VIA_JVM_OPTS=value-from-file", "-Xss120m")
      )
    }
  }
}
