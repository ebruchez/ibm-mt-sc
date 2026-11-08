# IBM MT/SC (Model 6310 / 6320) Emulator

An emulator, assembler, disassembler, and interactive Operator / Customer Engineer (CE) console for the **1967 IBM Magnetic Tape "Selectric" Composer (MT/SC)** cold-type typesetting system and its **16-bit Composer Control Unit (CCU)**.

Built in **Scala 3** and cross-compiled for **JVM**, **Scala.js (Web UI)**, **Scala Native**, and bare-metal **RP2040 (Adafruit Feather RP2040)**.

---

## Overview

Introduced in 1967, the IBM MT/SC combined magnetic tape recording with a modified IBM Selectric Composer and a dedicated 16-bit Solid Logic Dense (SLD) minicomputer—the **Model 6320 Composer Control Unit (CCU)**—with **2,048 bytes of 3-D magnetic core memory**. The CCU reads coded text from dual magnetic tape transports (Model 6310), looks up proportional character escapement widths (3–9 units), computes line justification, and drives the Selectric Composer keylevers at 14.1 characters per second through a 52-link electromechanical Translator.

This project emulates the complete MT/SC playback station at two selectable levels of hardware fidelity:

- **Gate-Level ILD Engine (`IldCcu`, default)**: Steps the 240 kHz crystal oscillator single-shots (`SSA` / `SSB`) through every 4-bit nibble (`B1-4`..`B13-16`) and Instruction Time (`I-0`..`I-9`), implementing the 1967 Maintenance Diagram Manual Intermediate Logic Diagrams (`ILD 0303`–`0312`) and the 3-D 4-plane magnetic core matrix (`CoreMatrix3D`).
- **Behavioral ISA Engine (`Ccu`)**: Instruction-level execution engine for fast behavioral verification and co-simulation against the gate-level engine.

---

## Features

- **Dual-Layer Console (Operator & Customer Engineer Modes)**:
  - **Operator Faceplate**: 12-key setup panel (`START`, `0`–`9`, `R`) and 11 setup prompt lamps (`CLEAR`, `MERGE`, `MODE`, `LINE COUNT`, `MEASURE`, `LINE INDENT`, `PARA INDENT`, `LEADER SPACE`, `MIN IW`, `MAX IW`, `MAX QUAD`, `LINE EXPAND`, `DEAD KEY`).
  - **Exposed CE Console**: Unlatch the operator faceplate to reveal the 5-position **Clock Control** switch (`Bit`, `Word`, `P.S.`, `Jump`, `Auto`), 5-position **Edit Memory Selector** switch (`Proc`, `Edit`, `Addr`, `Data`, `Prog`), `Display 4..1` pushbuttons, 50-signal logic probe selector (`TEST 4..1`), and the silk-screened **Initial Loader** bootstrap table.
- **Cold-Boot Program Loading**: Key the authentic 3-instruction (6-byte) bootstrap loader into core memory (`0x02A`–`0x02F`) and boot microprograms directly from a virtual program tape.
- **Peripherals & Galley Proof Output**:
  - Dual Magnetic Tape Reader stations (Right / Station 1 and Left / Station 2 for merge/correction tapes).
  - Electromechanical Translator magnet/clutch telemetry and proportional Selectric Composer galley proof rendering (full justification, flush left, flush right, centered, and dot leaders).
- **Symbolic Assembler & Disassembler**: Two-pass assembler and disassembler supporting all 8 CCU instruction classes, indirect byte addressing with auto-increment (`[Rn]++`), and self-modifying subroutine linkage (`JMPL`).
- **Web UI & ANSI Terminal UI**: Run interactively in the browser (with Web Audio mechanical sound synthesis and live gate-level backplane telemetry) or in an ANSI terminal.

---

## Running the Emulator

### 1. Web UI (Scala.js)

Compile the Scala.js bundle and serve the root directory with any static HTTP server:

```bash
sbt coreJS/fastLinkJS
python3 -m http.server 8000
```

Then open `http://localhost:8000/web/` in your browser.

### 2. Terminal Console (JVM)

Run the interactive ANSI terminal console:

```bash
sbt "coreJVM/run"
```

Optional CLI flags:
- `--ild` / `--behavioral`: Select the gate-level ILD engine (default) or behavioral ISA engine.
- `--max` / `--2x` / `--5x`: Set execution pacing speed (default is authentic 1x / 14.1 cps).
- `--operator`: Start with the Operator faceplate installed over the CE console.
- `<path-to-file>`: Mount a text file as a magnetic tape cartridge on the Right Tape Reader at startup.

### 3. Bare-Metal Microcontroller (Adafruit Feather RP2040 + 2.9" eInk + 128×32 OLED FeatherWings)

Compile the emulator with Scala Native freestanding (`thumbv6m-none-eabi`, Cortex-M0+, Immix GC) and link the `.uf2` firmware image via the Pico SDK:

```bash
sbt buildFeatherRp2040
```

This produces `firmware/build-feather-rp2040/mtsc_feather_rp2040.uf2` (and `.elf`):
- **USB-C Serial ANSI Console**: Connect over USB CDC (`screen /dev/tty.usbmodem* 115200` or `minicom`) for the live interactive Customer Engineer & Operator console.
- **Adafruit 2.9" Grayscale eInk FeatherWing (`PID 4777`, `IL0373 / SSD1680`, `296×128`)**:
  - Plugs directly onto the Feather RP2040 (`SPI0`: `SCK=GPIO18`, `MOSI=GPIO19`, `ECS=D9/GPIO9`, `D/C=D10/GPIO10`, `SRCS=D6/GPIO8`, `SDCS=D5/GPIO7`).
  - **Proportional Press Roman Serif Galley Output**: Renders the Selectric Composer galley sheet in Landscape (`296×128`) at `2 px = 1 escapement unit` (`6–18 px` character widths matching the `3–9 unit` escapement table), complete with a top pica ruler (`0p`–`12p`), target measure pointer (`▼ 140u`), vertical margin guideline, and 2-digit non-repro line numbers.
  - **Progressive & Hybrid eInk Refresh**: Progressive non-flashing differential updates (`450 ms`) as characters and words are composed, automatic clean 8-line page turns, and a 4-level grayscale camera-ready proof refresh when composition halts (or via `[e]` / `epd` and `epd flip` in the console).
  - **FeatherWing Tactile Buttons (`A` = `D11/GPIO11`, `B` = `D12/GPIO12`, `C` = `D13/GPIO13`)**:
    - **Button A**: Start / Pause / Restart composition.
    - **Button B**: Scroll through completed galley lines or trigger a 4-grayscale proof refresh.
    - **Button C**: Master Reset CCU, rewind tape cartridge, and clear galley sheet.
- **Adafruit 128×32 Monochrome OLED FeatherWing (`PID 2900`, `SSD1306`, `I2C1` `0x3C`)**:
  - Can be stacked simultaneously alongside the 2.9" eInk FeatherWing (`SDA=GPIO2`, `SCL=GPIO3` on `I2C1`; zero bus conflict with `SPI0`) or omitted entirely (auto-detected via bounded-timeout I2C probe with internal pull-up verification).
  - **High-Speed Live Telemetry (`128×32`)**:
    - **Top Band**: Active console display mode (`CLK`, `DP1`–`DP4`, `OPR`), all 11 incandescent CE Console / Operator lamps (`LITE 11..1`), and machine status badge (`RUN`, `INH`, `MPX`, `HLT`, or inverted `ERR`).
    - **Middle Band**: Current line number (`L01`..`L99`), graphical escapement/justification progress bar (`0..targetMeasure`), and numeric unit counter (`084/140U`).
    - **Bottom Band**: Live `PC:hex3` (`PSAR`), `MT:dec4` (`6310` magnetic tape head frame), `GL:xxL` (completed lines), and `C:xxxx` (total characters composed).
  - **Stacked Button Safety**: `SDCS (D5/GPIO7)`, `SRCS (D6/GPIO8)`, and `ECS (D9/GPIO9)` are parked as `GPIO_IN` with pull-ups whenever `SPI0` is idle, preventing any push-pull contention with OLED Buttons A/B/C (`D9`, `D6`, `D5`) and allowing Buttons A/B/C on *both* FeatherWings to control the emulator. Use `[o]` / `:oled` to re-probe or `:oled flip` to rotate 180°.
- **Onboard `WS2812` NeoPixel (`GPIO 16`) Status Lamps**:
  - **Green (`+RUN`)**: `240 kHz` oscillator / CCU clock running.
  - **Amber (`LITE 11`)**: Waiting for Operator (`Inhibit` latch or Setup prompt active).
  - **Blue pulse (`+MT CALL`)**: Magnetic Tape `6310` stepping a frame.
  - **Red (`LITE 9` / `ERR LT`)**: Parity or Instruction Check error.

### 4. Running the Test Suite

```bash
sbt coreJVM/test
```

---

## Project Structure

```text
├── build.sbt                  # Scala 3 cross-project build (JVM, JS, Native)
├── core/
│   ├── shared/src/main/scala/mtsc/
│   │   ├── asm/               # Symbolic Assembler and Disassembler
│   │   ├── cli/               # ANSI terminal debugger & CLI entry point
│   │   ├── console/           # Operator & CE console state, lamps, and views
│   │   ├── cpu/               # Behavioral CCU engine & I/O bus
│   │   │   └── ild/           # Gate-level ILD 0303–0312 & 3-D Core Matrix engine
│   │   ├── diag/              # Microprograms (justification, escapement, math, diagnostics)
│   │   ├── isa/               # 16-bit instruction AST, Encoder, and Decoder
│   │   ├── memory/            # 2,048-byte CoreMemory and CoreImage loader
│   │   ├── periph/            # Dual Tape Readers, Cartridges, Translator, Composer paper
│   │   └── types/             # Core types (Word, Address, RegId, Device, Condition)
│   ├── shared/src/test/scala/ # MUnit test suites (ISA, Assembler, ILD gate-level, peripherals)
│   └── js/src/main/scala/     # Scala.js browser UI controller & Web Audio synthesis
├── web/                       # HTML/CSS frontend for the interactive Web UI
└── docs/produced/             # Project documentation & technical references
    ├── ccu-assembly-reference.md
    ├── bootloading.md
    ├── mtsc-in-numbers.md
    └── code-examples.md
```

---

## Documentation & Articles

### Blog Series

- [The IBM Magnetic Tape Selectric Composer - Part 1](https://blog.bruchez.name/posts/ibm-mt-sc-1/) — Historical context, system overview, and the MT/SR input station.
- [The IBM Magnetic Tape Selectric Composer - Part 2](https://blog.bruchez.name/posts/ibm-mt-sc-2/) — The MT/SC output station, Composer Control Unit (CCU), instruction set, and hardware architecture.

### Project Reference Docs

- [The IBM MT/SC in Technical Numbers](docs/produced/mtsc-in-numbers.md) — Hardware, gate/card counts, clock timing, memory map, and physical specifications.
- [CCU Assembly Language Reference](docs/produced/ccu-assembly-reference.md) — Complete ISA guide, binary encodings, addressing modes, and annotated assembly examples.
- [Cold-Booting & Initial Loader Guide](docs/produced/bootloading.md) — How the 3-step front-panel bootstrap loader and two-pass self-relocating program tape work.
