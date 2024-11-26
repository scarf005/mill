package mill.main.client

import org.junit.Assert._
import org.junit.{Ignore, Rule, Test}

import java.io.{ByteArrayOutputStream, File, PrintStream}
import java.nio.file.Files

class FileToStreamTailerTest {

  @Rule
  val retryRule = new RetryRule(3)

  @Test
  def handleNonExistingFile(): Unit = {
    val bas = new ByteArrayOutputStream()
    val ps = new PrintStream(bas)

    val file = File.createTempFile("tailer", "")
    assertTrue(file.delete())

    Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
      tailer.start()
      Thread.sleep(200)
      assertEquals(bas.toString, "")
    }
  }

  @Test
  def handleNoExistingFileThatAppearsLater(): Unit = {
    val bas = new ByteArrayOutputStream()
    val ps = new PrintStream(bas)

    val file = File.createTempFile("tailer", "")
    assertTrue(file.delete())

    Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
      tailer.start()
      Thread.sleep(100)
      assertEquals(bas.toString, "")

      Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
        out.println("log line")
        assertTrue(file.exists)
        Thread.sleep(100)
        assertEquals(bas.toString, "log line" + System.lineSeparator)
      }
    }
  }

  @Test
  def handleExistingInitiallyEmptyFile(): Unit = {
    val bas = new ByteArrayOutputStream()
    val ps = new PrintStream(bas)

    val file = File.createTempFile("tailer", "")
    assertTrue(file.exists)

    Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
      tailer.start()
      Thread.sleep(100)

      assertEquals(bas.toString, "")

      Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
        out.println("log line")
        assertTrue(file.exists)
        Thread.sleep(100)
        assertEquals(bas.toString, "log line" + System.lineSeparator)
      }
    }
  }

  @Test
  def handleExistingFileWithOldContent(): Unit = {
    val bas = new ByteArrayOutputStream()
    val ps = new PrintStream(bas)

    val file = File.createTempFile("tailer", "")
    assertTrue(file.exists)

    Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
      out.println("old line 1")
      out.println("old line 2")
      Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
        tailer.start()
        Thread.sleep(500)
        assertEquals(bas.toString, "")
        out.println("log line")
        assertTrue(file.exists)
        Thread.sleep(500)
        assertEquals(bas.toString.trim, "log line")
      }
    }
  }

  @Ignore
  @Test
  def handleExistingEmptyFileWhichDisappearsAndComesBack(): Unit = {
    val bas = new ByteArrayOutputStream()
    val ps = new PrintStream(bas)

    val file = File.createTempFile("tailer", "")
    assertTrue(file.exists)

    Using.resource(new FileToStreamTailer(file, ps, 10)) { tailer =>
      tailer.start()
      Thread.sleep(100)

      assertEquals(bas.toString, "")

      Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
        out.println("log line 1")
        out.println("log line 2")
        assertTrue(file.exists)
        Thread.sleep(100)
        assertEquals(
          bas.toString,
          "log line 1" + System.lineSeparator + "log line 2" + System.lineSeparator
        )
      }

      // Now delete file and give some time, then append new lines
      assertTrue(file.delete())
      Thread.sleep(100)

      Using.resource(new PrintStream(Files.newOutputStream(file.toPath))) { out =>
        out.println("new line")
        assertTrue(file.exists)
        Thread.sleep(100)
        assertEquals(
          bas.toString,
          "log line 1" + System.lineSeparator + "log line 2" + System.lineSeparator + "new line" + System.lineSeparator
        )
      }
    }
  }
}
