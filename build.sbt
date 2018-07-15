inThisBuild(Seq(
  version := "0.1.0-SNAPSHOT",
  organization := "be.doeraene",

  crossScalaVersions := Seq("2.12.6", "2.10.7", "2.11.12"),
  scalaVersion := crossScalaVersions.value.head,
  scalacOptions ++= Seq("-deprecation", "-feature", "-Xfatal-warnings"),

  homepage := Some(url("https://github.com/sjrd/scala-js-env-d8")),
  licenses += ("BSD New",
      url("https://github.com/sjrd/scala-js-env-d8/blob/master/LICENSE")),
  scmInfo := Some(ScmInfo(
      url("https://github.com/sjrd/scala-js-env-d8"),
      "scm:git:git@github.com:sjrd/scala-js-env-d8.git",
      Some("scm:git:git@github.com:sjrd/scala-js-env-d8.git"))),

  logBuffered := false
))

val commonSettings = Def.settings(
  // Scaladoc linking
  autoAPIMappings := true,

  publishMavenStyle := true,
  publishTo := {
    val nexus = "https://oss.sonatype.org/"
    if (isSnapshot.value)
      Some("snapshots" at nexus + "content/repositories/snapshots")
    else
      Some("releases" at nexus + "service/local/staging/deploy/maven2")
  },
  pomExtra := (
    <developers>
      <developer>
        <id>sjrd</id>
        <name>Sébastien Doeraene</name>
        <url>https://github.com/sjrd/</url>
      </developer>
    </developers>
  ),
  pomIncludeRepository := { _ => false }
)

lazy val root: Project = project.in(file(".")).
  settings(
    publishArtifact in Compile := false,
    publish := {},
    publishLocal := {},

    clean := clean.dependsOn(
      clean in `scalajs-env-d8`,
      clean in `test-project`
    ).value
  )

lazy val `scalajs-env-d8`: Project = project.in(file("d8-env")).
  settings(
    commonSettings,

    libraryDependencies ++= Seq(
      "org.scala-js" %% "scalajs-js-envs" % scalaJSVersion,

      "com.novocode" % "junit-interface" % "0.11" % "test",
      "org.scala-js" %% "scalajs-js-envs-test-kit" % scalaJSVersion % "test"
    ),

    testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a", "-s"),
    parallelExecution in Test := false
  )

lazy val `test-project`: Project = project.
  enablePlugins(ScalaJSPlugin).
  enablePlugins(ScalaJSJUnitPlugin).
  settings(
    scalaJSUseMainModuleInitializer := true,
    jsEnv := new be.doeraene.scalajsd8env.D8JSEnv()
  )
