package mill.runner.client

import java.lang.reflect.Method
import scala.util.Try

object MillNoServerLauncher {
  case class LoadResult(millMainMethod: Option[Method], loadTime: Long) {
    val canLoad: Boolean = millMainMethod.isDefined
  }

  private var canLoad: Option[LoadResult] = None

  def load(): LoadResult = {
    canLoad match {
      case Some(result) => result
      case None =>
        val startTime = System.currentTimeMillis()
        val millMainMethod = Try {
          val millMainClass = MillNoServerLauncher.getClass.getClassLoader.loadClass("mill.runner.MillMain")
          millMainClass.getMethod("main", classOf[Array[String]])
        }.toOption

        val loadTime = System.currentTimeMillis() - startTime
        val result = LoadResult(millMainMethod, loadTime)
        canLoad = Some(result)
        result
    }
  }

  def runMain(args: Array[String]): Unit = {
    val loadResult = load()
    if (loadResult.millMainMethod.isDefined) {
      val exitVal = MillProcessLauncher.launchMillNoServer(args)
      System.exit(exitVal)
    } else {
      throw new RuntimeException("Cannot load mill.runner.MillMain class")
    }
  }
}
