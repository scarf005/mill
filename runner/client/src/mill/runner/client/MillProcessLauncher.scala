package mill.runner.client

import mill.main.client.OutFiles._
import mill.main.client.{EnvVars, ServerFiles, Util}
import java.io.{File, IOException}
import java.nio.file.{Files, Path, Paths}
import java.util.{Comparator, Properties, UUID}
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{ArrayBuffer, Buffer}

object MillProcessLauncher {
  def launchMillNoServer(args: Array[String]): Int = {
    val setJnaNoSys = System.getProperty("jna.nosys") == null
    val sig = f"${UUID.randomUUID().hashCode}%08x"
    val processDir = Paths.get(".").resolve(out).resolve(millNoServer).resolve(sig)

    val l = new ArrayBuffer[String]()
    l ++= millLaunchJvmCommand(setJnaNoSys)
    l += "mill.runner.MillMain"
    l += processDir.toAbsolutePath.toString
    l ++= Util.readOptsFileLines(millOptsFile()).asScala
    l ++= args

    val builder = new ProcessBuilder().command(l.asJava).inheritIO()

    var interrupted = false

    try {
      val p = configureRunMillProcess(builder, processDir)
      MillProcessLauncher.runTermInfoThread(processDir)
      p.waitFor()
    } catch {
      case e: InterruptedException =>
        interrupted = true
        throw e
    } finally {
      if (!interrupted) {
        // cleanup if process terminated for sure
        Files.walk(processDir)
          // depth-first
          .sorted(Comparator.reverseOrder())
          .forEach(p => p.toFile.delete())
      }
    }
  }

  def launchMillServer(serverDir: Path, setJnaNoSys: Boolean): Unit = {
    val l = new ArrayBuffer[String]()
    l ++= millLaunchJvmCommand(setJnaNoSys)
    l += "mill.runner.MillServerMain"
    l += serverDir.toFile.getCanonicalPath

    val builder = new ProcessBuilder()
      .command(l.asJava)
      .redirectOutput(serverDir.resolve(ServerFiles.stdout).toFile)
      .redirectError(serverDir.resolve(ServerFiles.stderr).toFile)

    configureRunMillProcess(builder, serverDir)
  }

  def configureRunMillProcess(builder: ProcessBuilder, serverDir: Path): Process = {
    val sandbox = serverDir.resolve(ServerFiles.sandbox)
    Files.createDirectories(sandbox)
    builder.environment().put(EnvVars.MILL_WORKSPACE_ROOT, new File("").getCanonicalPath)

    builder.directory(sandbox.toFile)
    builder.start()
  }

  def millJvmOptsFile(): File = {
    val millJvmOptsPath = Option(System.getenv(EnvVars.MILL_JVM_OPTS_PATH))
      .filter(_.trim.nonEmpty)
      .getOrElse(".mill-jvm-opts")
    new File(millJvmOptsPath).getAbsoluteFile
  }

  def millOptsFile(): File = {
    val millJvmOptsPath = Option(System.getenv(EnvVars.MILL_OPTS_PATH))
      .filter(_.trim.nonEmpty)
      .getOrElse(".mill-opts")
    new File(millJvmOptsPath).getAbsoluteFile
  }

  def millJvmOptsAlreadyApplied(): Boolean = {
    Option(System.getProperty("mill.jvm_opts_applied")).contains("true")
  }

  def millServerTimeout(): String = System.getenv(EnvVars.MILL_SERVER_TIMEOUT_MILLIS)

  def isWin: Boolean = System.getProperty("os.name", "").startsWith("Windows")

  def javaExe(): String = {
    Option(System.getProperty("java.home"))
      .filter(_.nonEmpty)
      .flatMap { javaHome =>
        val exePath = new File(
          javaHome + File.separator + "bin" + File.separator + "java" + (if (isWin) ".exe" else "")
        )
        if (exePath.exists) Some(exePath.getAbsolutePath) else None
      }
      .getOrElse("java")
  }

  def millClasspath(): Array[String] = {
    val selfJars = {
      val millOptionsPath = Option(System.getProperty("MILL_OPTIONS_PATH"))
      millOptionsPath match {
        case Some(path) =>
          // read MILL_CLASSPATH from file MILL_OPTIONS_PATH
          val millProps = new Properties()
          try {
            val is = Files.newInputStream(Paths.get(path))
            try {
              millProps.load(is)
            } finally {
              is.close()
            }
          } catch {
            case e: IOException =>
              throw new RuntimeException(s"Could not load '$path'", e)
          }

          millProps.stringPropertyNames.asScala
            .find(_ == "MILL_CLASSPATH")
            .map(millProps.getProperty)
            .getOrElse("")

        case None =>
          // read MILL_CLASSPATH from file sys props
          Option(System.getProperty("MILL_CLASSPATH"))
            .getOrElse {
              // We try to use the currently local classpath as MILL_CLASSPATH
              System.getProperty("java.class.path").replace(File.pathSeparator, ",")
            }
      }
    }

    if (selfJars.trim.isEmpty) {
      throw new RuntimeException("MILL_CLASSPATH is empty!")
    }

    selfJars.split("[,]").map(jar => new File(jar).getCanonicalPath)
  }

  def millLaunchJvmCommand(setJnaNoSys: Boolean): Buffer[String] = {
    val vmOptions = ArrayBuffer[String]()

    // Java executable
    vmOptions += javaExe()

    // jna
    if (setJnaNoSys) {
      vmOptions += "-Djna.nosys=true"
    }

    // sys props
    val sysProps = System.getProperties
    for {
      k <- sysProps.stringPropertyNames.asScala
      if k.startsWith("MILL_") && k != "MILL_CLASSPATH"
    } {
      vmOptions += s"-D$k=${sysProps.getProperty(k)}"
    }

    Option(millServerTimeout()).foreach { timeout =>
      vmOptions += s"-Dmill.server_timeout=$timeout"
    }

    // extra opts
    val millJvmOptsFile = this.millJvmOptsFile()
    if (millJvmOptsFile.exists) {
      vmOptions ++= Util.readOptsFileLines(millJvmOptsFile).asScala
    }

    vmOptions += "-cp"
    vmOptions += millClasspath().mkString(File.pathSeparator)

    vmOptions
  }

  def readMillJvmOpts(): java.util.List[String] = Util.readOptsFileLines(millJvmOptsFile())

  def getTerminalDim(s: String, inheritError: Boolean): Int = {
    val proc = new ProcessBuilder()
      .command("tput", s)
      .redirectOutput(ProcessBuilder.Redirect.PIPE)
      .redirectInput(ProcessBuilder.Redirect.INHERIT)
      // We cannot redirect error to PIPE, because `tput` needs at least one of the
      // outputstreams inherited so it can inspect the stream to get the console
      // dimensions. Instead, we check up-front that `tput cols` and `tput lines` do
      // not raise errors, and hope that means it continues to work going forward
      .redirectError(
        if (inheritError) ProcessBuilder.Redirect.INHERIT else ProcessBuilder.Redirect.PIPE
      )
      .start()

    val exitCode = proc.waitFor()
    if (exitCode != 0) throw new Exception("tput failed")
    new String(proc.getInputStream.readAllBytes()).trim.toInt
  }

  def writeTerminalDims(tputExists: Boolean, serverDir: Path): Unit = {
    val str = {
      if (!tputExists) "0 0"
      else {
        try {
          if (java.lang.System.console() == null) "0 0"
          else s"${getTerminalDim("cols", true)} ${getTerminalDim("lines", true)}"
        } catch {
          case _: Exception => "0 0"
        }
      }
    }
    Files.write(serverDir.resolve(ServerFiles.terminfo), str.getBytes)
  }

  def runTermInfoThread(serverDir: Path): Unit = {
    val termInfoPropagatorThread = new Thread(
      new Runnable {
        def run(): Unit = {
          try {
            val tputExists = try {
              getTerminalDim("cols", false)
              getTerminalDim("lines", false)
              true
            } catch {
              case _: Exception => false
            }
            while (true) {
              writeTerminalDims(tputExists, serverDir)
              Thread.sleep(100)
            }
          } catch {
            case _: Exception => // Ignore
          }
        }
      },
      "TermInfoPropagatorThread"
    )
    termInfoPropagatorThread.start()
  }
}
