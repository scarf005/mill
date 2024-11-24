package mill.runner.client

import mill.main.client.{OutFiles, ServerCouldNotBeStarted, ServerLauncher, Util}
import mill.main.client.lock.Locks
import MillProcessLauncher.millOptsFile
import java.nio.file.Path
import scala.jdk.CollectionConverters._

/**
 * Main entry point for the Mill build tool client.
 */
object MillClientMain {
  def main(args: Array[String]): Unit = {
    val runNoServer = shouldRunNoServer(args)

    if (runNoServer) {
      // start in no-server mode
      MillNoServerLauncher.runMain(args)
    } else {
      try {
        // start in client-server mode
        val optsArgs = Util.readOptsFileLines(millOptsFile()).asScala.toBuffer
        optsArgs.appendAll(args)

        val launcher = new ServerLauncher(
          System.in,
          System.out,
          System.err,
          System.getenv(),
          optsArgs.toArray,
          null,
          -1
        ) {
          override def initServer(serverDir: Path, setJnaNoSys: Boolean, locks: Locks): Unit = {
            MillProcessLauncher.launchMillServer(serverDir, setJnaNoSys)
          }

          override def preRun(serverDir: Path): Unit = {
            MillProcessLauncher.runTermInfoThread(serverDir)
          }
        }

        var exitCode = launcher.acquireLocksAndRun(OutFiles.out).exitCode
        if (exitCode == Util.ExitServerCodeWhenVersionMismatch()) {
          exitCode = launcher.acquireLocksAndRun(OutFiles.out).exitCode
        }

        System.exit(exitCode)
      } catch {
        case e: ServerCouldNotBeStarted =>
          System.err.println(
            """|Could not start a Mill server process.
               |This could be caused by too many already running Mill instances or by an unsupported platform.
               |${e.getMessage}
               |""".stripMargin
          )

          if (MillNoServerLauncher.load().canLoad) {
            System.err.println("Trying to run Mill in-process ...")
            MillNoServerLauncher.runMain(args)
          } else {
            System.err.println(
              """|Loading Mill in-process isn't possible.
                 |Please check your Mill installation!""".stripMargin
            )
            throw e
          }
      }
    }
  }

  private def shouldRunNoServer(args: Array[String]): Boolean = {
    if (args.isEmpty) {
      false
    } else {
      val firstArg = args(0)
      val noServerArgs = Set("--interactive", "--no-server", "--repl", "--bsp", "--help")
      val isNoServerArg = noServerArgs.contains(firstArg) || firstArg.startsWith("-i")

      if (isNoServerArg) {
        true
      } else {
        // WSL2 has the directory /run/WSL/ and WSL1 not
        Option(System.getProperty("os.version")).exists { osVersion =>
          osVersion.contains("icrosoft") || osVersion.contains("WSL")
        }
      }
    }
  }
}
