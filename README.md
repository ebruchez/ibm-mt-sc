# IBM MT/SC (Model 6310 / 6320) Emulator

An emulator, assembler, disassembler, and interactive Operator / Customer Engineer (CE) console for the **1967 IBM Magnetic Tape "Selectric" Composer (MT/SC)** cold-type typesetting system and its **16-bit Composer Control Unit (CCU)**.

Built in **Scala 3** and cross-compiled for **JVM**, **Scala.js (Web UI)**, and **Scala Native**.

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

### 3. Running the Test Suite

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
