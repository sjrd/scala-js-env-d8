addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.0.0-M3")

libraryDependencies += "org.scala-js" %% "scalajs-js-envs" % "1.0.0-M3"

unmanagedSourceDirectories in Compile +=
  baseDirectory.value.getParentFile / "d8-env/src/main/scala"
