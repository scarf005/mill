package mill.main.client

import mill.main.client.OutFiles._
import mill.main.client.lock.{Locks, TryLocked}

import java.io.{InputStream, OutputStream, PrintStream}
import java.net.Socket
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._
import scala.util.Using

/**
 * Client side code that interacts with `Server.scala` in order to launch a generic
 * long-lived background server.
 *
 * The protocol is as follows:
 *
 * - Client:
 *   - Take clientLock
 *   - If processLock is not yet taken, it means server is not running, so spawn a server
 *   - Wait for server socket to be available for connection
 * - Server:
 *   - Take processLock.
 *     - If already taken, it means another server was running
 *       (e.g. spawned by a different client) so exit immediately
 * - Server: loop:
 *   - Listen for incoming client requests on serverSocket
 *   - Execute client request
 *   - If clientLock is released during execution, terminate server (otherwise
 *     we have no safe way of terminating the in-process request, so the server
 *     may continue running for arbitrarily long with no client attached)
 *   - Send `ProxyStream.END` packet and call `clientSocket.close()`
 * - Client:
 *   - Wait for `ProxyStream.END` packet or `clientSocket.close()`,
 *     indicating server has finished execution and all data has been received
 */
abstract class ServerLauncher(
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    env: Map[String, String],
    args: Array[String],
    memoryLocks: Array[Locks],
    forceFailureForTestingMillisDelay: Int
) {

  case class Result(exitCode: Int, serverDir: Path)

  private val serverProcessesLimit = 5
  private val serverInitWaitMillis = 10000

  def initServer(serverDir: Path, b: Boolean, locks: Locks): Unit
  def preRun(serverDir: Path): Unit

  def acquireLocksAndRun(outDir: String): Result = {
    val setJnaNoSys = Option(System.getProperty("jna.nosys")).isEmpty
    if (setJnaNoSys) {
      System.setProperty("jna.nosys", "true")
    }

    val versionAndJvmHomeEncoding =
      Util.sha1Hash(BuildInfo.millVersion + System.getProperty("java.home"))

    var serverIndex = 0
    while (serverIndex < serverProcessesLimit) { // Try each possible server process (-1 to -5)
      serverIndex += 1
      val serverDir =
        Paths.get(outDir, millServer, s"$versionAndJvmHomeEncoding-$serverIndex")
      Files.createDirectories(serverDir)

      val locks = if (memoryLocks != null) memoryLocks(serverIndex - 1)
                 else Locks.files(serverDir.toString)

      try {
        val clientLocked = locks.clientLock.tryLock()
        try {
          if (clientLocked.isLocked) {
            preRun(serverDir)
            val exitCode = run(serverDir, setJnaNoSys, locks)
            return Result(exitCode, serverDir)
          }
        } finally {
          clientLocked.close()
          locks.close()
        }
      } catch {
        case _: Exception => // Try next server index
      }
    }
    throw new ServerCouldNotBeStarted(
      s"Reached max server processes limit: $serverProcessesLimit"
    )
  }

  private def run(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Int = {
    val serverPath = serverDir.resolve(ServerFiles.runArgs)
    Using.resource(Files.newOutputStream(serverPath)) { f =>
      f.write(if (System.console() != null) 1 else 0)
      Util.writeString(f, BuildInfo.millVersion)
      Util.writeArgs(args, f)
      Util.writeMap(env.asJava, f)
    }

    if (locks.processLock.probe()) {
      initServer(serverDir, setJnaNoSys, locks)
    }

    while (locks.processLock.probe()) Thread.sleep(3)

    val retryStart = System.currentTimeMillis()
    var ioSocket: Socket = null
    var socketThrowable: Throwable = null
    while (ioSocket == null && System.currentTimeMillis() - retryStart < serverInitWaitMillis) {
      try {
        val port = Integer.parseInt(Files.readString(serverDir.resolve(ServerFiles.socketPort)))
        ioSocket = new Socket("127.0.0.1", port)
      } catch {
        case e: Throwable =>
          socketThrowable = e
          Thread.sleep(10)
      }
    }

    if (ioSocket == null) {
      throw new Exception("Failed to connect to server", socketThrowable)
    }

    val outErr = ioSocket.getInputStream
    val in = ioSocket.getOutputStream
    val outPumper = new ProxyStream.Pumper(outErr, stdout, stderr)
    val inPump = new InputPumper(() => stdin, () => in, true)
    val outPumperThread = new Thread(outPumper, "outPump")
    val inThread = new Thread(inPump, "inPump")
    outPumperThread.setDaemon(true)
    inThread.setDaemon(true)
    outPumperThread.start()
    inThread.start()

    if (forceFailureForTestingMillisDelay > 0) {
      Thread.sleep(forceFailureForTestingMillisDelay)
      throw new Exception(s"Force failure for testing: $serverDir")
    }
    outPumperThread.join()

    try {
      val exitCodeFile = serverDir.resolve(ServerFiles.exitCode)
      Files.readAllLines(exitCodeFile).get(0).toInt
    } catch {
      case _: Throwable => Util.ExitClientCodeCannotReadFromExitCodeFile()
    } finally {
      ioSocket.close()
    }
  }
}
