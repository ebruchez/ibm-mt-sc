ThisBuild / organization := "org.mtsc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.0"

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, core.native)
  .settings(
    name := "ibm-mt-sc-emulator",
    publish / skip := true
  )

lazy val core = crossProject(JSPlatform, JVMPlatform, NativePlatform)
  .in(file("core"))
  .settings(
    name := "mtsc-core",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    libraryDependencies ++= Seq(
      "org.scalameta" %%% "munit" % "1.3.6" % Test
    )
  )
  .jsSettings(
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("mtsc.web.WebMain")
  )
  .jvmSettings(
    Compile / mainClass := Some("mtsc.cli.Main")
  )
