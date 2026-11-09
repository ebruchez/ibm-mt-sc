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

lazy val checkRp2040Lint = taskKey[Unit](
  "Verify RP2040 firmware ELF against forbidden heavy symbols, hot-path allocations, and XIP cache footprint limits"
)

def runRp2040Lint(elfFile: File, log: sbt.util.Logger): Unit = {
  if (!elfFile.exists()) {
    sys.error(s"RP2040 ELF file not found: $elfFile (run buildFeatherRp2040 first)")
  }
  val nmOut = Seq("arm-none-eabi-nm", "-S", elfFile.getAbsolutePath).!!
  val nmLines = nmOut.linesIterator.toVector

  // 1. Forbidden heavy runtime classes / subsystems on RP2040 (264 KB SRAM)
  val forbiddenPatterns = Seq(
    "java.util.Formatter"             -> "String.format / f\"...\" (~95 KB flash, regex + Locale allocations)",
    "scala.scalanative.regex."        -> "Regex engine (syntax trees / NFA states on heap)",
    "java.util.regex.Pattern"         -> "java.util.regex.Pattern (pulls in regex engine)",
    "java.lang.StringSpecialCasing"   -> "Unicode StringSpecialCasing table (allocates 109 CaseEntry objects + TimSort)",
    "scala.Console$"                  -> "scala.Console$ (allocates 12 KB of Console.in BufferedReader buffers)",
    "java.util.stream.StreamSupport"  -> "java.util.stream pipeline (Spliterator/Pipeline allocations)"
  )
  val forbiddenHits = for {
    (pattern, reason) <- forbiddenPatterns
    matching = nmLines.filter(_.contains(pattern))
    if matching.nonEmpty
  } yield s"  - $pattern ($reason): ${matching.size} symbol(s), e.g. ${matching.head.trim}"

  if (forbiddenHits.nonEmpty) {
    sys.error(
      s"[RP2040 Lint] Forbidden heavy symbols linked into ${elfFile.getName}:\n" +
        forbiddenHits.mkString("\n")
    )
  }

  // 2. Zero-allocation hot-path verification (emulation bit-time loop + console rendering + idle polling)
  val objdumpOut = Seq("arm-none-eabi-objdump", "-d", elfFile.getAbsolutePath).!!
  val hotFunctionMarkers = Seq(
    "_SM19mtsc.cpu.ild.IldCcuD15stepBitTimeFast",
    "_SM25mtsc.console.AnsiTerminalD20renderDirtyRowsChars",
    "_SM22mtsc.asm.Disassembler$D17writeDisassembled",
    "_SM27mtsc.console.CeConsoleView$D",
    "_SM20mtsc.periph.OledView$D",
    "_SM23mtsc.rp2040.Rp2040Main$D19updateHardwareLamps",
    "_SM23mtsc.rp2040.Rp2040Main$D24servicePendingEpdRefresh",
    "_SM23mtsc.rp2040.Rp2040Main$D17updateOledDisplay",
    "_SM23mtsc.rp2040.Rp2040Main$D13flushTerminal"
  )
  val allocRegex = """<(scalanative_(?:GC_)?alloc[^>]+|_SM32scala\.scalanative\.runtime\.Boxes\$D\d+boxTo[^>]+)>""".r
  val hotAllocViolations = scala.collection.mutable.ArrayBuffer.empty[String]
  for (block <- objdumpOut.split("\n\n")) {
    val lines = block.linesIterator.toVector
    if (lines.nonEmpty) {
      val header = lines.head
      if (hotFunctionMarkers.exists(header.contains)) {
        val allocs = allocRegex.findAllMatchIn(block).map(_.group(1)).toSeq.distinct
        if (allocs.nonEmpty) {
          hotAllocViolations += s"  - $header calls ${allocs.mkString(", ")}"
        }
      }
    }
  }
  if (hotAllocViolations.nonEmpty) {
    sys.error(
      s"[RP2040 Lint] Heap allocations found in zero-allocation hot path:\n" +
        hotAllocViolations.mkString("\n")
    )
  }

  // 3. XIP Flash Cache footprint check for IldCcu.stepBitTimeFast (< 8 KB = 1 way of 16 KB 2-way XIP cache)
  val stepBitTimeSymbol = "_SM19mtsc.cpu.ild.IldCcuD15stepBitTimeFastiEPT19mtsc.cpu.ild.IldCcu"
  val stepLineOpt = nmLines.find(line => line.endsWith(s" $stepBitTimeSymbol"))
  stepLineOpt match {
    case Some(line) =>
      val parts = line.trim.split("\\s+")
      val addrHex = parts(0)
      val sizeBytes = Integer.parseInt(parts(1), 16)
      val maxXipWayBytes = 8192
      if (sizeBytes > maxXipWayBytes) {
        sys.error(
          f"[RP2040 Lint] IldCcu.stepBitTimeFast size is $sizeBytes bytes (0x$sizeBytes%x), " +
            s"exceeding single XIP cache way limit ($maxXipWayBytes bytes)!"
        )
      }
      log.info(
        f"[RP2040 Lint] OK: 0 forbidden symbols, 0 hot-path allocations, " +
          f"stepBitTimeFast=$sizeBytes bytes @ 0x$addrHex (< $maxXipWayBytes B XIP cache way)"
      )
    case None =>
      sys.error(s"[RP2040 Lint] Symbol $stepBitTimeSymbol not found in ${elfFile.getName}")
  }
}

lazy val root = project
  .in(file("."))
  .aggregate(core.jvm, core.js, core.native)
  .settings(
    name := "ibm-mt-sc-emulator",
    publish / skip := true,
    buildFeatherRp2040 := (rp2040 / buildFeatherRp2040).value,
    checkRp2040Lint := (rp2040 / checkRp2040Lint).value
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
        .withMode(Mode.releaseFast)
        .withGC(GC.immix)
        .withBuildTarget(BuildTarget.libraryStatic)
        .withMultithreading(false)
        .withTargetTriple("thumbv6m-none-eabi")
        .withBaseName("mtsc_rp2040")
        .withCompileOptions(
          c.compileOptions ++ Seq(
            s"--sysroot=$armSysroot",
            "-mcpu=cortex-m0plus",
            "-O2",
            "-funwind-tables",
            "-fshort-enums",
            "-ffunction-sections",
            "-fdata-sections"
          )
        )
    },
    checkRp2040Lint := {
      val log = streams.value.log
      val firmwareDir = (ThisBuild / baseDirectory).value / "firmware"
      val elfFile = firmwareDir / "build-feather-rp2040" / "mtsc_feather_rp2040.elf"
      runRp2040Lint(elfFile, log)
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

      val elfFile = buildDir / "mtsc_feather_rp2040.elf"
      runRp2040Lint(elfFile, log)

      val uf2File = buildDir / "mtsc_feather_rp2040.uf2"
      log.info(s"Built Adafruit Feather RP2040 UF2 firmware: $uf2File (${uf2File.length()} bytes)")
      uf2File
    }
  )
