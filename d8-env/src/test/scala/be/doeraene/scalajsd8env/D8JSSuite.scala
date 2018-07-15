package be.doeraene.scalajsd8env

import org.scalajs.jsenv.test._

import org.junit.runner.RunWith

@RunWith(classOf[JSEnvSuiteRunner])
class D8JSSuite extends JSEnvSuite(D8JSSuite.Config)

object D8JSSuite {
  val Config = {
    JSEnvSuiteConfig(new D8JSEnv)
      .withAwaitTimepout(scala.concurrent.duration.DurationInt(5).seconds)
  }
}
