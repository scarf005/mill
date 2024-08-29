package mill.bsp.worker

import ch.epfl.scala.bsp4j.{
  ScalaBuildServer,
  ScalaMainClass,
  ScalaMainClassesItem,
  ScalaMainClassesParams,
  ScalaMainClassesResult,
  ScalaTestClassesItem,
  ScalaTestClassesParams,
  ScalaTestClassesResult,
  ScalacOptionsItem,
  ScalacOptionsParams,
  ScalacOptionsResult
}
import mill.{Agg, T}
import mill.bsp.worker.Utils.sanitizeUri
import mill.util.Jvm
import mill.scalalib.{JavaModule, ScalaModule, TestModule, UnresolvedPath}
import mill.testrunner.{Framework, TestRunnerUtils}
import sbt.testing.Fingerprint

import java.util.concurrent.CompletableFuture
import scala.jdk.CollectionConverters._
import scala.util.chaining.scalaUtilChainingOps

private trait MillScalaBuildServer extends ScalaBuildServer { this: MillBuildServer =>

  override def buildTargetScalacOptions(p: ScalacOptionsParams)
      : CompletableFuture[ScalacOptionsResult] =
    completableTasks(
      hint = s"buildTarget/scalacOptions ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: JavaModule =>
          val scalacOptionsTask = m match {
            case m: ScalaModule => m.allScalacOptions
            case _ => T.task { Seq.empty[String] }
          }

          val compileClasspathTask =
            if (enableJvmCompileClasspathProvider) {
              // We have a dedicated request for it
              T.task { Agg.empty[UnresolvedPath] }
            } else {
              m.bspCompileClasspath
            }

          val classesPathTask =
            if (clientWantsSemanticDb) {
              m.bspCompiledClassesAndSemanticDbFiles
            } else {
              m.bspCompileClassesPath
            }

          T.task {
            (scalacOptionsTask(), compileClasspathTask(), classesPathTask())
          }
      }
    ) {
      // We ignore all non-JavaModule
      case (
            ev,
            state,
            id,
            m: JavaModule,
            (allScalacOptions, compileClasspath, classesPathTask)
          ) =>
        val pathResolver = ev.pathsResolver
        new ScalacOptionsItem(
          id,
          allScalacOptions.asJava,
          compileClasspath.iterator
            .map(_.resolve(pathResolver))
            .map(sanitizeUri).toSeq.asJava,
          sanitizeUri(classesPathTask.resolve(pathResolver))
        )
    } {
      new ScalacOptionsResult(_)
    }

  override def buildTargetScalaMainClasses(p: ScalaMainClassesParams)
      : CompletableFuture[ScalaMainClassesResult] =
    completableTasks(
      hint = "buildTarget/scalaMainClasses",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = { case m: JavaModule =>
        T.task((m.zincWorker().worker(), m.mainClass(), m.localRunClasspath(), m.forkArgs(), m.forkEnv()))
      }
    ) {
      case (ev, state, id, m: JavaModule, (worker, mainClass, localRunClasspath, forkArgs, forkEnv)) =>
        // We find all main classes, although we could also find only the configured one
        val mainClasses = worker.discoverMainClasses(localRunClasspath.map(_.path))
        // val mainMain = m.mainClass().orElse(if(mainClasses.size == 1) mainClasses.headOption else None)
        val items = (mainClass.toList ++ mainClasses).distinct.map { mc =>
          val scalaMc = new ScalaMainClass(mc, Seq().asJava, forkArgs.asJava)
          scalaMc.setEnvironmentVariables(forkEnv.map(e => s"${e._1}=${e._2}").toSeq.asJava)
          scalaMc
        }
        new ScalaMainClassesItem(id, items.asJava)

      case (ev, state, id, _, _) => // no Java module, so no main classes
        new ScalaMainClassesItem(id, Seq.empty[ScalaMainClass].asJava)
    } {
      new ScalaMainClassesResult(_)
    }

  override def buildTargetScalaTestClasses(p: ScalaTestClassesParams)
      : CompletableFuture[ScalaTestClassesResult] =
    completableTasks(
      s"buildTarget/scalaTestClasses ${p}",
      targetIds = _ => p.getTargets.asScala.toSeq,
      tasks = {
        case m: TestModule =>
          T.task(Some((m.runClasspath(), m.testFramework(), m.testClasspath())))
        case _ =>
          T.task(None)
      }
    ) {
      case (ev, state, id, m: TestModule, Some((classpath, testFramework, testClasspath))) =>
        val (frameworkName, classFingerprint): (String, Agg[(Class[_], Fingerprint)]) =
          Jvm.inprocess(
            classpath.map(_.path),
            classLoaderOverrideSbtTesting = true,
            isolated = true,
            closeContextClassLoaderWhenDone = false,
            cl => {
              val framework = Framework.framework(testFramework)(cl)
              val discoveredTests = TestRunnerUtils.discoverTests(
                cl,
                framework,
                Agg.from(testClasspath.map(_.path))
              )
              (framework.name(), discoveredTests)
            }
          )(new mill.api.Ctx.Home { def home = os.home })
        val classes = Seq.from(classFingerprint.map(classF => classF._1.getName.stripSuffix("$")))
        new ScalaTestClassesItem(id, classes.asJava).tap { it =>
          it.setFramework(frameworkName)
        }
      case (ev, state, id, _, _) =>
        // Not a test module, so no test classes
        new ScalaTestClassesItem(id, Seq.empty[String].asJava)
    } {
      new ScalaTestClassesResult(_)
    }

}
