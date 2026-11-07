import scala.scalanative.build._
import scala.sys.process._

ThisBuild / organization := "org.mtsc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.10.0"

lazy val armSysroot: String =
  Seq("arm-none-eabi-gcc", "-print-sysroot").!!.trim

lazy val buildFeatherRp2040 = taskKey[File](
  "Link the Scala Native RP2040 static library and build the Adafruit Feather RP2040 UF2 firmware image"
)

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, core.native)
  .settings(
    name := "ibm-mt-sc-emulator",
    publish / skip := true,
    buildFeatherRp2040 := (rp2040 / buildFeatherRp2040).value
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
  .nativeSettings(
    scalaVersion := "3.3.8",
    Compile / mainClass := Some("mtsc.cli.Main")
  )

lazy val rp2040 = project
  .in(file("rp2040"))
  .enablePlugins(ScalaNativePlugin)
  .settings(
    name := "mtsc-rp2040",
    scalaVersion := "3.3.8",
    publish / skip := true,
    Compile / mainClass := None,
    Compile / selectMainClass := None,
    Compile / discoveredMainClasses := Seq.empty,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
    ),
    Compile / unmanagedSourceDirectories += (ThisBuild / baseDirectory).value / "core" / "shared" / "src" / "main" / "scala",
    nativeConfig ~= { c =>
      c.withLTO(LTO.none)
        .withMode(Mode.releaseSize)
        .withGC(GC.immix)
        .withBuildTarget(BuildTarget.libraryStatic)
        .withMultithreading(false)
        .withTargetTriple("thumbv6m-none-eabi")
        .withBaseName("mtsc_rp2040")
        .withCompileOptions(
          c.compileOptions ++ Seq(
            s"--sysroot=$armSysroot",
            "-mcpu=cortex-m0plus",
            "-Oz",
            "-fshort-enums",
            "-ffunction-sections",
            "-fdata-sections"
          )
        )
    },
    buildFeatherRp2040 := {
      val log = streams.value.log
      val staticLib = (Compile / nativeLink).value
      log.info(s"Linked RP2040 Scala Native static library: $staticLib")

      val firmwareDir = (ThisBuild / baseDirectory).value / "firmware"
      val buildDir = firmwareDir / "build-feather-rp2040"
      IO.createDirectory(buildDir)

      val configExit = Process(
        Seq(
          "cmake",
          "-DPICO_BOARD=adafruit_feather_rp2040",
          s"-DSCALA_NATIVE_LIB=${staticLib.getAbsolutePath}",
          ".."
        ),
        buildDir
      ).!(log)
      if (configExit != 0) {
        sys.error(s"CMake configuration failed with exit code $configExit")
      }

      val buildExit = Process(
        Seq("cmake", "--build", ".", "--parallel", "8"),
        buildDir
      ).!(log)
      if (buildExit != 0) {
        sys.error(s"CMake build failed with exit code $buildExit")
      }

      val uf2File = buildDir / "mtsc_feather_rp2040.uf2"
      log.info(s"Built Adafruit Feather RP2040 UF2 firmware: $uf2File (${uf2File.length()} bytes)")
      uf2File
    }
  )
