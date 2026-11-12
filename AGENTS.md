# AGENTS.md — Developer & Agent Guide

Technical guide for building, testing, linting, flashing, and debugging the **IBM MT/SC (Model 6310 / 6320) Emulator** across JVM, Scala.js, Scala Native, and bare-metal **RP2040**.

---

## 1. Project Architecture & Targets

- **`core` (`coreJVM`, `coreJS`, `coreNative`)**:
  - Shared Scala 3 implementation (`core/shared/src/main/scala/mtsc/`) of the 1966 **IBM 6320 Composer Control Unit (CCU)**, **Model 6310** dual magnetic tape transports, **Selectric Composer** proportional escapement/justification microcode, symbolic assembler/disassembler, and Customer Engineer (CE) / Operator consoles.
  - **Dual Execution Engines**:
    - **`IldCcu` (Gate-Level ILD Engine — Default)**: Evaluates the 1967 IBM Maintenance Diagram Manual Intermediate Logic Diagrams (`ILD 0301`–`0312`) and the 3-D `16×16×64` ferrite-core matrix (`CoreMatrix3D`) across every nibble Bit-Time (`8` or `12` pulses of the `240 kHz` `SSA`/`SSB` single-shot clock).
    - **`Ccu` (Behavioral Engine)**: Instruction-level reference engine verified in lockstep against `IldCcu`.
- **`rp2040` + `firmware/`**:
  - Compiles the shared emulator and [`Rp2040Main.scala`](rp2040/src/main/scala/mtsc/rp2040/Rp2040Main.scala) via **Scala Native freestanding** (`thumbv6m-none-eabi`, Cortex-M0+, Immix GC) into `libmtsc_rp2040.a`, then links it with the **Pico SDK** C runtime ([`firmware/main.c`](firmware/main.c), [`epd_ssd1680.c`](firmware/epd_ssd1680.c), [`oled_ssd1306.c`](firmware/oled_ssd1306.c)) into `mtsc_feather_rp2040.elf` and `.uf2`.

---

## 2. Building & Running Tests

### Unit Tests
Run the MUnit test suite before committing changes:
```bash
sbt coreJVM/test
```
To run across all host platforms (JVM, Scala.js, and host Scala Native):
```bash
sbt test
```

### Web UI (Scala.js)
```bash
sbt coreJS/fastLinkJS
python3 -m http.server 8000
# Open http://localhost:8000/web/
```

### Terminal Console (JVM)
```bash
sbt "coreJVM/run"
```

### Bare-Metal RP2040 Firmware (`mtsc_feather_rp2040.uf2` / `.elf`)
Requires `arm-none-eabi-gcc`, `cmake`, and the Pico SDK (`$PICO_SDK_PATH` or default `$HOME/Projects/pico/pico-sdk`):
```bash
sbt buildFeatherRp2040
```
This task:
1. Links the Scala Native static library (`rp2040/target/scala-3.3.8/libmtsc_rp2040.a`).
2. Configures and builds the Pico SDK CMake project in `firmware/build-feather-rp2040/`.
3. Automatically runs **`checkRp2040Lint`** on `firmware/build-feather-rp2040/mtsc_feather_rp2040.elf`.

You can also run the linter standalone against an existing ELF build:
```bash
sbt checkRp2040Lint
```

---

## 3. RP2040 Hardware Constraints & `checkRp2040Lint` Rules

The RP2040 has **264 KB of total SRAM** (`~162 KB` usable Immix GC heap after static data and a `16 KB` C heap reserve) and a **16 KB 2-way set-associative XIP flash cache** (`8 KB` per way). [`build.sbt`](build.sbt) enforces three strict post-link checks in `checkRp2040Lint`:

1. **No Forbidden Heavy Runtime Symbols (`arm-none-eabi-nm`)**:
   - **Never use**:
     - `String.format` or `f"..."` string interpolation (`java.util.Formatter` links ~95 KB of flash plus regex and `Locale` tables).
     - `scala.util.matching.Regex` or `java.util.regex.Pattern`.
     - `String.toLowerCase` / `toUpperCase` (`java.lang.StringSpecialCasing` allocates 109 `CaseEntry` objects and `TimSort` buffers at startup).
     - `scala.Console` / `println` (initializes `12 KB` of `Console.in` `BufferedReader` buffers).
     - `java.util.stream.*`.
   - **Use instead**: Zero-allocation formatting helpers in [`mtsc.types.Fmt`](core/shared/src/main/scala/mtsc/types/Fmt.scala) (`Fmt.hex3`, `Fmt.dec4`, `Fmt.asciiLower`, etc.) and `PicoSdk.pico_serial_print`.

2. **Zero Heap Allocations or Boxing in Hot Paths (`arm-none-eabi-objdump -d`)**:
   - The following functions are disassembled and verified to contain **zero calls** to `scalanative_alloc*` or `Boxes.boxTo*`:
     - `IldCcu.stepBitTimeFast`
     - `AnsiTerminal.renderDirtyRowsChars`
     - `Disassembler.writeDisassembled`
     - `CeConsoleView.*`
     - `OledView.*`
     - `Rp2040Main.updateHardwareLamps`, `servicePendingEpdRefresh`, `updateOledDisplay`, `flushTerminal`
   - Inside these functions, use primitive `Int`/`Boolean` locals and pre-allocated arrays; avoid `Option`, tuples, non-inlined closures, `String` concatenation, or `CUnsignedInt` boxing across method boundaries.

3. **XIP Flash Cache Footprint Limit (`< 8,192` Bytes)**:
   - `IldCcu.stepBitTimeFast` must fit inside a single `8 KB` way of the RP2040 XIP cache (currently `~4.4 KB`) so the gate-level bit-time loop executes at **`~25 µs/Bit-Time` (`39 kHz`, `~1.6×` faster than the 1966 CCU)** without flash cache thrashing.

---

## 4. Flashing & Debugging with the Raspberry Pi Debug Probe (SWD)

When a **Raspberry Pi Debug Probe** (`CMSIS-DAPv2`, USB VID:PID `2e8a:000c`) is connected to the Feather RP2040's `SWDIO`, `SWCLK`, and `GND` pins, you can flash and inspect the target over SWD without touching the `BOOTSEL` or `Reset` buttons.

### Flashing `mtsc_feather_rp2040.elf` via OpenOCD
```bash
openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
  -c "adapter speed 5000" \
  -c "program firmware/build-feather-rp2040/mtsc_feather_rp2040.elf verify reset exit"
```

### Resetting the Target via OpenOCD
```bash
openocd -f interface/cmsis-dap.cfg -f target/rp2040.cfg \
  -c "adapter speed 5000" \
  -c "init; reset run; exit"
```

### Live Non-Destructive State Inspection via `arm-none-eabi-gdb`
Attach to the running RP2040 (`USE_CORE 0` so OpenOCD binds GDB to Core 0), halt the Cortex-M0+ core, inspect firmware state, and resume execution cleanly in a single batch command:
```bash
arm-none-eabi-gdb -nx -batch \
  firmware/build-feather-rp2040/mtsc_feather_rp2040.elf \
  -ex 'target extended-remote | openocd -f interface/cmsis-dap.cfg -c "set USE_CORE 0" -f target/rp2040.cfg -c "adapter speed 5000" -c "gdb_port pipe; log_output /dev/null"' \
  -ex "monitor halt" \
  -ex "bt" \
  -ex "p boot_stage" \
  -ex "p low_clock_mode" \
  -ex "p oled_present" \
  -ex "p oled_diag_frames" \
  -ex "p oled_diag_last_rc" \
  -ex "monitor resume" \
  -ex "detach" \
  -ex "quit"
```

### Useful Firmware Symbols to Inspect in GDB
- **`boot_stage` (`uint32_t` in [`firmware/main.c`](firmware/main.c))**:
  - `1`: Inside `ScalaNativeInit()` (initializing Immix GC & static constructors).
  - `2`: Entered `mtsc_rp2040_main()`, initializing `I2C1` OLED, `SPI0` eInk, `PIO0` NeoPixel, and `MtscSystem`.
  - `3`–`11`: Initializing `IldCcu`, `CeConsoleState`, assembling initial microprogram, loading escapement tables, building the 3-tape catalog, and rendering the initial eInk splash screen.
  - `12`: Main interactive loop tick (polling the 6 pushbuttons and servicing eInk refreshes).
  - `13`–`14`: Rendering `CeConsoleView` and flushing dirty rows over USB CDC serial.
  - `15`: Idle wait inside `pico_getchar_timeout_us()` (or low-power `WFE` sleep after 30s inactivity).
  - `16`: Dispatching an interactive command or button action.
- **`low_clock_mode` (`uint8_t` in [`firmware/main.c`](firmware/main.c))**:
  - `0`: Full speed `125 MHz` (`clk_sys` and `clk_peri` clocked from `pll_sys`).
  - `1`: Low-power idle `48 MHz` (`clk_sys` and `clk_peri` switched to `pll_usb`, `pll_sys` powered off).
  - *Important hardware invariant*: Never drop `clk_sys` below `48 MHz` (e.g., to `12 MHz` `XOSC`) while USB is active without masking interrupts (`save_and_disable_interrupts()`), because the RP2040 USB controller requires `clk_sys >= 48 MHz` and `USBCTRL_IRQ` (`dcd_rp2040_irq`) will panic on an endpoint transfer if it fires during a `12 MHz` transition. Always call `pico_restore_full_clock()` before `I2C1` transfers so `I2C1` runs at `400 kHz`.
- **OLED Diagnostics ([`firmware/oled_ssd1306.c`](firmware/oled_ssd1306.c))**:
  - `oled_present` (`bool`), `oled_bus_initialized` (`bool`), `oled_diag_frames` (`uint32_t`).
  - `oled_diag_last_rc` (`int32_t`): `513` = full 512-byte frame + `0x40` control byte ACKed; `-1` (`PICO_ERROR_GENERIC`) = NACK; `-2` (`PICO_ERROR_TIMEOUT`) = `I2C1` timeout; `-100` = `SDA`/`SCL` stuck low.
- **Crash / Exception Telemetry ([`firmware/main.c`](firmware/main.c))**:
  - `last_thrown_obj`, `last_throw_caller`: Captured by `__wrap_scalanative_throw` whenever a Scala exception is thrown.
  - `hardfault_entered`, `fault_frame[0..7]`, `fault_sp`, `fault_lr`: Captured by `isr_hardfault` / `hardfault_c_handler`.

### Running the On-Device Hardware Benchmark Over USB Serial
When connected over USB-C (`/dev/cu.usbmodem*`), you can trigger the non-destructive hardware benchmark (`[b]` → [`Rp2040Main.runHardwareBenchmark`](rp2040/src/main/scala/mtsc/rp2040/Rp2040Main.scala)) from the command line:
```bash
python3 -c '
import os, tty, select, time, re
fd = os.open("/dev/cu.usbmodem201301", os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)
tty.setraw(fd); time.sleep(0.2)
while select.select([fd], [], [], 0.05)[0]: os.read(fd, 8192)
os.write(fd, b"b"); buf = bytearray(); deadline = time.time() + 0.6
while time.time() < deadline:
    if select.select([fd], [], [], 0.05)[0]: buf.extend(os.read(fd, 8192))
os.close(fd)
clean = re.sub(r"\x1b\[[0-9;?]*[a-zA-Z]", "", buf.decode("utf-8", errors="ignore"))
for m in re.finditer(r"Bench:[^\r\n│]+", clean): print(m.group(0))
'
```

---

## 5. Hardware Pinout & Bus Coexistence Reference

| Peripheral / Control | Pins / Bus on Adafruit Feather RP2040 | Notes |
| :--- | :--- | :--- |
| **2.9" eInk FeatherWing (`#4777`)** | `SPI0`: `SCK=GPIO18`, `MOSI=GPIO19`, `MISO=GPIO20`, `ECS=D9 (GPIO9)`, `D/C=D10 (GPIO10)`, `SRCS=D6 (GPIO8)`, `SDCS=D5 (GPIO7)` | `IL0373 / UC8151D` (default) or `SSD1680` (`[E]` to toggle). When `SPI0` is idle and an OLED Wing is stacked, `ECS/SRCS/SDCS` park as `GPIO_IN` with pull-ups (`100 kΩ` on `#4777`) to prevent `3.3 V`–`GND` shorts if OLED Wing buttons (`D9/D6/D5`) are pressed. |
| **128×32 OLED FeatherWing (`#2900`)** | `I2C1`: `SDA=GPIO2`, `SCL=GPIO3` (`0x3C` @ `400 kHz`) | Uses bounded `i2c_write_timeout_us` only (`100 ms` frame timeout) and 9-clock `SCL` bus-recovery if `SDA` is ever held low across a reset. |
| **Button 1 — Green** | `D12` (`GPIO 12`, active-LOW) | Click: `START / PAUSE` · Hold (`≥ 0.7 s`): Toggle `14.1 cps` vs. `Batch` pacing |
| **Button 2 — Yellow** | `D13` (`GPIO 13`, active-LOW) | Click: `SINGLE STEP` · Hold (`≥ 0.5 s`): `~10 Hz` Auto-Step. (`pico_led_put` skips `GPIO 13` when buttons are active to avoid driving HIGH against Button 2.) |
| **Button 3 — Red** | `D4` (`GPIO 6`, active-LOW) | Click: `TAPE / MASTER RESET` · Hold (`≥ 0.7 s`): Cycle Next Tape (`[1/3]`–`[3/3]`) |
| **Button 4 — White** | `D11` (`GPIO 11`, active-LOW) | Click: `GALLEY ▲` (`-4` lines) · Hold (`≥ 0.7 s`): Jump to Top (`L01`) |
| **Button 5 — Blue** | `A0` (`GPIO 26`, active-LOW) | Click: `GALLEY ▼ / PROOF` (`+4` lines or 4-gray proof) · Hold (`≥ 0.7 s`): Jump to Bottom |
| **Button 6 — Black** | `A1` (`GPIO 27`, active-LOW) | Click: `CE MODE` (`DP1..4`, `CLK`, `OPR`) · Hold (`≥ 0.7 s`): Cycle Measure (`140u`/`110u`/`90u`) |
| **Onboard Status NeoPixel** | `GPIO 16` (`PIO0` SM0 `WS2812`) | Wait `80 µs` after writing `0x000000` before gating `CLK_SYS_PIO0` in `SLEEPDEEP`. |
