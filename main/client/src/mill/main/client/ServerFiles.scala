package mill.main.client

import java.io.File

/**
 * Central place containing all the files that live inside the `out/mill-server-*` folder
 * and documentation about what they do
 */
object ServerFiles {
  val serverId = "serverId"
  val sandbox = "sandbox"

  /**
   * Ensures only a single client is manipulating each mill-server folder at
   * a time, either spawning the server or submitting a command. Also used by
   * the server to detect when a client disconnects, so it can terminate execution
   */
  val clientLock = "clientLock"

  /**
   * Lock file ensuring a single server is running in a particular mill-server
   * folder. If multiple servers are spawned in the same folder, only one takes
   * the lock and the others fail to do so and terminate immediately.
   */
  val processLock = "processLock"

  /**
   * The port used to connect between server and client
   */
  val socketPort = "socketPort"

  /**
   * The pipe by which the client snd server exchange IO
   *
   * Use uniquely-named pipes based on the fully qualified path of the project folder
   * because on Windows the un-qualified name of the pipe must be globally unique
   * across the whole filesystem
   */
  def pipe(base: String): String = {
    try {
      s"$base/mill-${Util.md5hex(new File(base).getCanonicalPath).substring(0, 8)}-io"
    } catch {
      case e: Exception => throw new RuntimeException(e)
    }
  }

  /**
   * Log file containing server housekeeping information
   */
  val serverLog = "server.log"

  /**
   * File that the client writes to pass the arguments, environment variables,
   * and other necessary metadata to the Mill server to kick off a run
   */
  val runArgs = "runArgs"

  /**
   * File the server writes to pass the exit code of a completed run back to the
   * client
   */
  val exitCode = "exitCode"

  /**
   * Where the server's stdout is piped to
   */
  val stdout = "stdout"

  /**
   * Where the server's stderr is piped to
   */
  val stderr = "stderr"

  /**
   * Terminal information that we need to propagate from client to server
   */
  val terminfo = "terminfo"
}
