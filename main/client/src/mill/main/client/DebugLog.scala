package mill.main.client

import java.nio.file._

/**
 * Used to add `println`s in scenarios where you can't figure out where on earth
 * your stdout/stderr/logs are going, and so we just dump them in a file in your
 * home folder so you can find them
 */
object DebugLog {
  def println(s: String): Unit = synchronized {
    val path = Paths.get(System.getProperty("user.home"), "mill-debug-log.txt")
    try {
      if (!Files.exists(path)) Files.createFile(path)
      Files.writeString(path, s + "\n", StandardOpenOption.APPEND)
    } catch {
      case e: java.io.IOException => throw new RuntimeException(e)
    }
  }
}
