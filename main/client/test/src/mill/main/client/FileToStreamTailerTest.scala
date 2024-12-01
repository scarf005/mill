package mill.main.client

import utest._

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files

object FileToStreamTailerTest extends TestSuite {
  val tests = Tests {
    test("handleNonExistingFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.delete())

      Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
        tailer.start()
        Thread.sleep(200)
        assert(bas.toString == "")
      }
    }

    test("handleNoExistingFileThatAppearsLater") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.delete())

      Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
          out.println("log line")
          assert(file.exists)
          Thread.sleep(100)
          assert(bas.toString == "log line" + System.lineSeparator)
        }
      }
    }

    test("handleExistingInitiallyEmptyFile") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists)

      Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
        tailer.start()
        Thread.sleep(100)

        assert(bas.toString == "")

        Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
          out.println("log line")
          assert(file.exists)
          Thread.sleep(100)
          assert(bas.toString == "log line" + System.lineSeparator)
        }
      }
    }

    test("handleExistingFileWithOldContent") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists)

      Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
        out.println("old line 1")
        out.println("old line 2")
        Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
          tailer.start()
          Thread.sleep(500)
          assert(bas.toString == "")
          out.println("log line")
          assert(file.exists)
          Thread.sleep(500)
          assert(bas.toString.trim == "log line")
        }
      }
    }

    test.ignore("handleExistingEmptyFileWhichDisappearsAndComesBack") {
      val bas = new ByteArrayOutputStream()
      val ps = new PrintStream(bas)

      val file = File.createTempFile("tailer", "")
      assert(file.exists)

      Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
        tailer.start()
        Thread.sleep(100)
        assert(bas.toString == "")

        Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
          out.println("log line 1")
          Thread.sleep(100)
          assert(bas.toString == "log line 1" + System.lineSeparator)

          assert(file.delete())
          Thread.sleep(100)

          Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out2 =>
            out2.println("log line 2")
            Thread.sleep(100)
            assert(bas.toString == "log line 1" + System.lineSeparator + "log line 2" + System.lineSeparator)
          }
        }
      }
    }
  }
}
