package mill.main.client

import java.io._
import java.math.BigInteger
import java.nio.charset.{Charset, StandardCharsets}
import java.security.{MessageDigest, NoSuchAlgorithmException}
import java.util.{Base64, Scanner}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Using

object Util {
  // use methods instead of constants to avoid inlining by compiler
  @noinline def ExitClientCodeCannotReadFromExitCodeFile(): Int = 1
  @noinline def ExitServerCodeWhenIdle(): Int = 0
  @noinline def ExitServerCodeWhenVersionMismatch(): Int = 101

  val isWindows: Boolean = System.getProperty("os.name").toLowerCase.startsWith("windows")
  val isJava9OrAbove: Boolean = !System.getProperty("java.specification.version").startsWith("1.")
  private val utf8: Charset = Charset.forName("UTF-8")

  def parseArgs(argStream: InputStream): Array[String] = {
    val argsLength = readInt(argStream)
    Array.fill(argsLength) {
      readString(argStream)
    }
  }

  def writeArgs(args: Array[String], argStream: OutputStream): Unit = {
    writeInt(argStream, args.length)
    args.foreach(writeString(argStream, _))
  }

  /**
   * This allows the mill client to pass the environment as it sees it to the
   * server (as the server remains alive over the course of several runs and
   * does not see the environment changes the client would)
   */
  def writeMap(map: java.util.Map[String, String], argStream: OutputStream): Unit = {
    writeInt(argStream, map.size)
    map.forEach { (key, value) =>
      writeString(argStream, key)
      writeString(argStream, value)
    }
  }

  def parseMap(argStream: InputStream): java.util.Map[String, String] = {
    val env = new java.util.HashMap[String, String]()
    val mapLength = readInt(argStream)
    for (_ <- 0 until mapLength) {
      val key = readString(argStream)
      val value = readString(argStream)
      env.put(key, value)
    }
    env
  }

  def readString(inputStream: InputStream): String = {
    // Result is between 0 and 255, hence the loop.
    val length = readInt(inputStream)
    val arr = new Array[Byte](length)
    var total = 0
    while (total < length) {
      val res = inputStream.read(arr, total, length - total)
      if (res == -1) throw new IOException("Incomplete String")
      else {
        total += res
      }
    }
    new String(arr, utf8)
  }

  def writeString(outputStream: OutputStream, string: String): Unit = {
    val bytes = string.getBytes(utf8)
    writeInt(outputStream, bytes.length)
    outputStream.write(bytes)
  }

  def writeInt(out: OutputStream, i: Int): Unit = {
    out.write((i >>> 24).toByte)
    out.write((i >>> 16).toByte)
    out.write((i >>> 8).toByte)
    out.write(i.toByte)
  }

  def readInt(in: InputStream): Int = {
    ((in.read() & 0xFF) << 24) +
    ((in.read() & 0xFF) << 16) +
    ((in.read() & 0xFF) << 8) +
    (in.read() & 0xFF)
  }

  /**
   * @return Hex encoded MD5 hash of input string.
   */
  def md5hex(str: String): String = {
    hexArray(MessageDigest.getInstance("md5").digest(str.getBytes(StandardCharsets.UTF_8)))
  }

  private def hexArray(arr: Array[Byte]): String = {
    String.format("%0" + (arr.length << 1) + "x", new BigInteger(1, arr))
  }

  def sha1Hash(path: String): String = {
    val md = MessageDigest.getInstance("SHA1")
    md.reset()
    val pathBytes = path.getBytes(StandardCharsets.UTF_8)
    md.update(pathBytes)
    val digest = md.digest()
    Base64.getEncoder.encodeToString(digest)
  }

  /**
   * Reads a file, ignoring empty or comment lines, interpolating env variables.
   *
   * @return The non-empty lines of the files or an empty list, if the file does not exist
   */
  def readOptsFileLines(file: File): List[String] = {
    val vmOptions = mutable.ListBuffer[String]()
    try {
      Using.resource(new Scanner(file)) { sc =>
        val env = System.getenv().asScala.toMap
        while (sc.hasNextLine) {
          val arg = sc.nextLine()
          val trimmed = arg.trim
          if (trimmed.nonEmpty && !trimmed.startsWith("#")) {
            vmOptions += interpolateEnvVars(arg, env)
          }
        }
      }
    } catch {
      case _: FileNotFoundException => // ignored
    }
    vmOptions.toList
  }

  /**
   * Interpolate variables in the form of <code>${VARIABLE}</code> based on the given Map <code>env</code>.
   * Missing vars will be replaced by the empty string.
   */
  def interpolateEnvVars(input: String, env: Map[String, String]): String = {
    val matcher = Util.envInterpolatorPattern.matcher(input)
    val result = new StringBuffer()

    while (matcher.find()) {
      val matched = matcher.group(1)
      if (matched == "$") {
        matcher.appendReplacement(result, "\\$")
      } else {
        val envVarValue = env.getOrElse(matched, "")
        matcher.appendReplacement(result, envVarValue)
      }
    }

    matcher.appendTail(result) // Append the remaining part of the string
    result.toString
  }

  private val envInterpolatorPattern =
    java.util.regex.Pattern.compile("\\$\\{(\\$|[A-Z_][A-Z0-9_]*)\\}")
}
