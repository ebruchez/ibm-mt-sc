# IBM MT/SC RP2040 Emulator — User Manual

A standalone hardware recreation of the **1966 IBM Magnetic Tape "Selectric" Composer (MT/SC)** and its **Model 6320 Composer Control Unit (CCU)** running on the **Adafruit Feather RP2040** with a **2.9" 4-Level Grayscale eInk Galley Display**, a **128×32 Monochrome OLED Console Panel**, and a **6-Button Color-Coded Control Panel**.

---

## 1. System Overview

| Component | Role in the IBM MT/SC Emulator |
| :--- | :--- |
| **Adafruit Feather RP2040** (`125 MHz` Cortex-M0+) | Runs the gate-level **IBM 6320 CCU** (`ILD 0301–0312` + 3-D `16×16×64` magnetic core matrix), **Model 6310** tape transports, and **Selectric Composer** escapement/justification microprogram in bare-metal Scala Native (`~1.6×` faster than original 1966 hardware in unpaced mode, or locked to `14.1` chars/sec in real-time mode). |
| **2.9" Grayscale eInk FeatherWing (`#4777`, `296×128`)** | **Selectric Composer Galley Proof Sheet** — renders proportional **Press Roman** serif type (`3`–`9` escapement units at `2 px/unit`) beneath a shaded pica ruler (`0p`–`12p`), right-margin target pointer (`▼ 140`), dotted margin guideline, and 2-digit non-repro line numbers (`01`–`64`). |
| **128×32 OLED FeatherWing (`#2900`, `SSD1306`)** | **Live 6320 CE & Operator Console** — shows the 11 incandescent indicator lamps (`LITE 11..1`), machine state badge (`RUN`, `INH`, `MPX`, `HLT`, `ERR`), graphical line-justification gauge, Program Step Address (`PC`), Magnetic Tape head position (`MT`), completed lines (`GL`), and character count (`C`). |
| **6 Color-Coded Pushbuttons** | **Operator & Customer Engineer Control Panel** — full standalone control over starting/pausing composition, single-stepping instructions, rewinding/switching magnetic tapes, scrolling the galley sheet, cycling CE lamp modes, and changing line measure. |
| **Onboard `WS2812` NeoPixel (`GPIO 16`)** | **Backplane Status Lamp** — `Green` (`+RUN`), `Blue` (`+MT CALL` tape read pulse), `Amber` (`LITE 11` Inhibit / Operator wait), or `Red` (`LITE 9` Parity / Instruction Check error). |

---

## 2. Quick-Start Guide

1. **Power On**: Plug in USB-C power (or a 3.7V LiPo battery).
   - The **2.9" eInk display** shows the 4-level grayscale **1966 IBM MT/SC promotional splash screen** (`IBM MT/SC — Selectric Composer — [Green] Start`).
   - The **128×32 OLED display** lights up in `DP1` mode showing the initial Program Counter (`PC:036`), tape head at frame `MT:0000`, and target line measure `000/140U`.
2. **Start Composing**: Press the **Green** button (**Short Press**).
   - The CCU reads coded text from the mounted magnetic tape cartridge, looks up each character's proportional escapement (`3`–`9` units), calculates interword spaces to justify the line to `140` units, and types onto the eInk galley sheet.
   - During composition, the eInk updates progressively using a **fast non-flashing monochrome waveform (`~380 ms`)**, while the OLED updates at `~25–30 Hz` as the line-justification progress bar fills from `000` to `140U`.
   - When the end of the tape is reached (`HALT`), the eInk automatically performs a **full 4-level grayscale camera-ready proof refresh (`~1.7 s`)**.
3. **Pause, Step, or Reset**:
   - Press **Green** (or **Yellow**) while composing to **pause** at the current instruction.
   - Press **Yellow** while paused to **single-step** one CCU instruction at a time (or **hold Yellow** to auto-step at `~10 Hz`).
   - Press **Red** at any time (running or paused) to **abort/reset** the CCU, rewind the magnetic tape to `0000`, and clear the galley sheet.

---

## 3. The 6 Pushbuttons — Reference Table

All 6 pushbuttons are wired **active-LOW** between their Feather pin and `GND` (using internal pull-ups and hardware-debounced 4 ms glitch filtering). Every button supports two actions:
- **Click (Short Press `< 0.7 s`)**: Primary operator/console action (triggers on release, or immediately when interrupting an active run).
- **Hold (Long Press `≥ 0.7 s`)**: Secondary configuration or navigation action (triggers as soon as the hold threshold is reached).

| # | Color | Feather Pin (`GPIO`) | Click (Short Press `< 0.7 s`) | Hold (Long Press `≥ 0.7 s`) |
| :-: | :--- | :--- | :--- | :--- |
| **1** | **Green** | `D12` (`GPIO 12`) | **START / PAUSE**<br>Start or resume composition (or pause if currently running). If the CCU is already `HALT`ed at the end of a tape, clicking Green automatically rewinds and starts a fresh run. | **TOGGLE PACING (`14 cps` ↔ `Batch`)**<br>Switch between authentic **14.1 cps Selectric Real-Time** pacing (`71 ms`/char) and unthrottled **Full-Speed CCU Batch** mode (`~2,350` instructions/sec). |
| **2** | **Yellow** | `D13` (`GPIO 13`) | **SINGLE STEP**<br>Execute exactly **one** 6320 CCU instruction (`I-0` through `I-1..I-9`) and update all OLED lamps and counters (or pause if currently running). | **AUTO-STEP (`~10 Hz`)**<br>Continuously step instructions at **`~10` instructions/sec** for as long as the button is held, animating the 11 OLED lamps and `PC` trace. |
| **3** | **Red** | `D4` (`GPIO 6`) | **TAPE / MASTER RESET**<br>Abort any active run, perform a 6320 **Master Reset** (`PSAR = 0x036`), rewind the current magnetic tape cartridge to frame `0000`, and clear the galley sheet. | **NEXT TAPE CARTRIDGE**<br>Cycle through the 3 built-in magnetic tape cartridges (`[1/3]` → `[2/3]` → `[3/3]`), mount on the Right Tape Reader, rewind, and reset. |
| **4** | **White** | `D11` (`GPIO 11`) | **GALLEY UP (`▲`)**<br>Scroll the eInk galley viewport **up 4 lines** toward line `01` and render a 4-level grayscale proof view. | **JUMP TO TOP OF GALLEY**<br>Jump immediately to the first 8 lines (`01`–`08`) at the top of the composed galley sheet. |
| **5** | **Blue** | `A0` (`GPIO 26`) | **GALLEY DOWN (`▼`) / PROOF**<br>Scroll the eInk galley viewport **down 4 lines** toward the bottom. If already at the bottom, triggers a full **4-level grayscale proof refresh**. | **JUMP TO BOTTOM OF GALLEY**<br>Jump immediately to the most recently composed lines at the bottom of the galley sheet. |
| **6** | **Black** | `A1` (`GPIO 27`) | **CE DISPLAY MODE**<br>Cycle the 11-lamp rotary indicator mode on the top band of the OLED: `DP1` → `DP2` → `DP3` → `DP4` → `CLK` → `OPR`. | **CYCLE LINE MEASURE**<br>Cycle the target justification width between **`140u`** (`11.7 picas`), **`170u`** (`14.2 picas`), and **`110u`** (`9.2 picas`), updating both displays. |

> [!TIP]
> **Interrupting a Run**: While the CCU is actively composing, pressing **Green** or **Yellow** pauses execution cleanly at the current instruction boundary (preserving your partial galley so you can single-step with **Yellow** or resume with **Green**), whereas pressing **Red** immediately aborts the run, resets the CCU, and rewinds the tape.

---

## 4. Reading the 128×32 OLED Display

The 128×32 monochrome OLED acts as a miniature **IBM 6320 Customer Engineer & Operator Console**, divided into three horizontal telemetry bands:

```text
┌────────────────────────────────────────────┐
│ DP1  ○ ● ○ · ○ ○ ● ● · ○ ● ● ○         RUN │  ← Band 1: Mode + 11 Lamps (LITE 11..1) + State
│ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ │
│ L04  [██████████████░░░░░░░░░░]   084/140U │  ← Band 2: Line # + Justification Bar + Units
│ ┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄┄ │
│ PC:1A4    MT:0142      GL:03L     C:0198   │  ← Band 3: Program Counter, Tape, Lines, Chars
└────────────────────────────────────────────┘
```

### Band 1 (Top): Console Mode, 11 Incandescent Lamps (`LITE 11..1`), and Status Badge
- **Mode Label (left)**: Cycled by clicking the **Black** button (`Btn 6`):
  - **`DP1` (Display 1 — `PSAR`)**: Lamps `11..1` show the 11-bit **Program Step Address Register** (current instruction address in core memory, grouped `3 | 4 | 4` bits).
  - **`DP2` (Display 2 — `MAL`)**: Lamps `11..1` show the 11-bit **Memory Address Latch** (effective operand/register address).
  - **`DP3` (Display 3 — `OP & BRANCH`)**: Lamps show the active 7-bit **Operation Code** (`OPL CDE 1..7`) and arithmetic condition latches (`LOW`, `EQUAL`).
  - **`DP4` (Display 4 — `P / Q BUFFERS`)**: Lamps show the 4-bit **P Buffer** (`P BFR 1..4`) and 4-bit **Q Buffer** (`Q BFR 1..4`) nibbles from the core memory sense amplifiers.
  - **`CLK` (Clock Default)**: Lamps show the active **Instruction Time** (`I-0`..`I-9`) and **Bit-Time** (`B1-4`..`B13-16`) state of the 6320 timing ring.
  - **`OPR` (Operator Faceplate)**: Lamps show the 11 **Operator Panel setup prompt lights** (`CLEAR`, `MERGE`, `MODE`, `LINE COUNT`, `MEASURE`, etc.).
- **11 Indicator Lamps (center)**: Drawn as filled circles (`●` = lit) or hollow rings (`○` = unlit), grouped into three nibbles (`LITE 11..9 · LITE 8..5 · LITE 4..1`) matching the physical 6320 lamp bank.
- **Machine Status Badge (right)**:
  - **`RUN`**: CCU clock ready or actively executing instructions.
  - **`INH`**: CCU stalled on **Inhibit** (`+INH` / `LITE 11`), waiting for an operator setup entry or I/O readiness.
  - **`MPX`**: **Multiplex** interrupt cycle active (Selectric Translator ready strobe).
  - **`HLT`**: Program halted at end of composition.
  - **`ERR` (inverted badge)**: Tape parity error or invalid instruction check (`LITE 9`).

### Band 2 (Middle): Line Number & Live Escapement / Justification Gauge
- **`L01`..`L99` (left)**: Current galley line number being composed.
- **Progress Bar (center)**: Fills from left to right as characters (`3`–`9` escapement units each) are buffered into the current line toward the right margin.
- **`084/140U` (right)**: Exact escapement units accumulated on the current line versus the active target line measure (`140U`, `170U`, or `110U`).

### Band 3 (Bottom): Live Machine Counters
- **`PC:xxx`**: 11-bit hexadecimal **Program Counter** (`PSAR`, `000`–`7FF`).
- **`MT:xxxx`**: 4-digit decimal **Model 6310 Magnetic Tape Reader** head position (frame index).
- **`GL:xxL`**: Number of completed justified lines on the galley sheet.
- **`C:xxxx`**: Total characters printed by the Selectric Composer Translator since reset.

---

## 5. Reading the 2.9" Grayscale eInk Galley Display

The 296×128 eInk display represents the physical **Selectric Composer camera-ready galley sheet** in landscape orientation (`2 pixels = 1 escapement unit`, where `1 unit = 1/72 inch = 1 point`):

- **Top Pica Ruler Bar (`y = 0..13`)**:
  - Marked in **picas** (`0p`, `3p`, `6p`, `9p`, `12p`, where `1 pica = 12 units = 24 pixels`), with `6-unit` half-pica ticks and `3-unit` quarter-pica ticks.
  - **Target Measure Badge (`▼ 140`)**: Indicates the active right-margin justification setting (`140u`, `170u`, or `110u`) and projects a vertical **dotted right-margin guideline** down the sheet.
- **Galley Viewport (`8` Lines Visible, `Up to 64` Lines Retained)**:
  - Each line is labeled in the left margin with a 2-digit non-repro proof line number (`01`..`64`).
  - Characters are rendered in an anti-aliased 4-level grayscale **Press Roman** proportional serif font whose pixel widths (`6 px` to `18 px`) match the authentic IBM Composer `3`–`9` unit escapement table.
  - When composition advances past line `08` (onto line `09`, `17`, etc.), the viewport automatically turns the page with a clean full refresh so there is zero ghosting from the previous page.
  - Use **White (`▲`)** and **Blue (`▼`)** at any time after or between runs to scroll back and forth through all composed lines (`01`–`64`).

---

## 6. Built-In Magnetic Tape Cartridges

Hold the **Red** button (`≥ 0.7 s`) to cycle through the three built-in magnetic tape cartridges stored in flash:

| Tape # | Cartridge Name | Description |
| :-: | :--- | :--- |
| **`[1/3]`** | **`Historical MT Tape`** *(Default)* | 4-paragraph historical overview of the 1966 IBM Magnetic Tape Selectric Composer, demonstrating multi-page composition, paragraph indentation, proportional escapement, and full right-margin justification. |
| **`[2/3]`** | **`6310 Merge Tape`** | Multi-paragraph technical overview of the dual Model 6310 tape transports, 6320 ferrite-core memory, and production galley composition workflow. |
| **`[3/3]`** | **`Press Roman Specimen`** | Single-page 10-point **Press Roman** type specimen displaying all **94 printable ASCII characters** (`A–Z`, `a–z`, `0–9`, all 32 punctuation/symbol glyphs `! " # $ % & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^ _ ` { | } ~`), and a 3-line justified pangram formatted to fit on one 8-line `140u` galley sheet. |

---

## 7. Onboard LEDs & Automatic Power Saving

### Onboard `WS2812` NeoPixel (`GPIO 16`)
- **Bright Green**: CCU running (`+RUN`), toggling the red `GPIO 13` activity indicator on each printed character.
- **Blue Flash**: Model 6310 Magnetic Tape Reader stepping a tape frame (`+MT CALL`).
- **Amber**: CCU in **Inhibit** (`LITE 11`), waiting for operator input.
- **Bright Red**: Tape parity or instruction check error (`LITE 9`).
- **Dim Green / Dim Cyan**: Idle and ready (`Dim Green` when USB serial is connected; `Dim Cyan` when running standalone on battery/USB power).

### 30-Second Automatic Inactivity Sleep
After **30 seconds** without button presses, composition activity, or USB serial input:
1. The **128×32 OLED display** turns off to prevent OLED burn-in.
2. The **2.9" eInk charge pump** powers down (while the bistable eInk paper retains the last 4-grayscale galley proof indefinitely at zero power).
3. The **onboard NeoPixel and status LED** turn off, and the RP2040 enters low-power `WFE` idle sleep.
4. **Instant Wake**: Pressing **any of the 6 buttons** (or sending any key over USB-C serial) immediately restores `125 MHz` system clock, wakes the OLED and NeoPixel, and executes the button's command.

---

## 8. Hardware Pinout & Optional USB-C Serial Console

### Pin Assignment Summary (Adafruit Feather RP2040)

| Subsystem | Signal / Button | Feather Label | RP2040 GPIO | Notes |
| :--- | :--- | :---: | :---: | :--- |
| **Pushbutton 1 (Green)** | `START / PAUSE` · Hold: `Pacing` | `D12` | `GPIO 12` | Active-LOW to `GND` (internal pull-up) |
| **Pushbutton 2 (Yellow)** | `STEP` · Hold: `Auto-Step` | `D13` | `GPIO 13` | Active-LOW to `GND` (internal pull-up) |
| **Pushbutton 3 (Red)** | `RESET / REWIND` · Hold: `Next Tape` | `D4` | `GPIO 6` | Active-LOW to `GND` (internal pull-up) |
| **Pushbutton 4 (White)** | `GALLEY ▲` · Hold: `Top` | `D11` | `GPIO 11` | Active-LOW to `GND` (internal pull-up) |
| **Pushbutton 5 (Blue)** | `GALLEY ▼ / PROOF` · Hold: `Bottom` | `A0` | `GPIO 26` | Active-LOW to `GND` (internal pull-up) |
| **Pushbutton 6 (Black)** | `CE MODE` · Hold: `Line Measure` | `A1` | `GPIO 27` | Active-LOW to `GND` (internal pull-up) |
| **2.9" eInk (`#4777`)** | `SPI0` (`SCK`, `MOSI`, `ECS`, `D/C`) | `SCK`, `MO`, `D9`, `D10` | `GPIO 18, 19, 9, 10` | Plus `SRCS=D6 (GPIO 8)`, `SDCS=D5 (GPIO 7)` |
| **128×32 OLED (`#2900`)** | `I2C1` (`SDA`, `SCL` @ `400 kHz`, `0x3C`) | `SDA`, `SCL` | `GPIO 2, 3` | Auto-detected; safe to omit or hot-probe |
| **Status LEDs** | Onboard `WS2812` NeoPixel | `NEOPIXEL` | `GPIO 16` | Live CCU backplane lamp color |

### Optional USB-C Interactive Console
While the emulator is designed to operate completely standalone via the 6 buttons, eInk, and OLED, you can also connect a terminal over USB-C (`115200` baud, e.g. `screen /dev/cu.usbmodem* 115200`) at any time to view the full `104×38` ANSI Customer Engineer console and use single-key shortcuts:
- `r` (Run/Pause), `s` (Step), `x` (Reset & Rewind), `p` (Toggle 14 cps / Batch pacing), `n` (Toggle `ILD` Gate-Level vs. `Behavioral` CCU engine)
- `e` (Force 4-Grayscale eInk refresh), `flip` (Rotate eInk 180°), `o` (Re-probe OLED), `b` (Run hardware timing benchmark), `d` (Run GC & show SRAM heap status)
