# The IBM MT/SC in Technical Numbers

**IBM Magnetic Tape "Selectric" Composer (Models 6310 / 6320 / 6354, 1967–1968)**

A technical summary of the hardware, logic packaging, timing, memory, instruction set, peripherals, and physical specifications of the IBM MT/SC system, compiled from primary IBM engineering documentation:
- D. A. Bishop, R. S. Heard, R. E. Hunt, J. E. Jones, and R. A. Rahenkamp, *"Development of the IBM Magnetic Tape SELECTRIC Composer"*, *IBM Journal of Research and Development*, Vol. 12, No. 5, pp. 380–398 (September 1968).
- *MT/SC Customer Engineering Instruction Manual*, IBM Form No. 241-5429-0 (August 1967).
- *6310/6320 Magnetic Tape "Selectric" Composer Maintenance Diagram Manual*, IBM Part No. 1202380 (1967–1968).

---

## 1. Headline Numbers at a Glance

| Metric | Value | Notes |
|---|---|---|
| **Logic Technology** | **Low-Speed SLD** | Higher-density packaging of IBM **SLT** (*Solid Logic Technology*) |
| **Average Gate Delay** | **~450 ns** | Chosen for minimum cost, noise immunity, and convection cooling |
| **Total Logic Circuits (Gates)** | **~1,000 circuits** | **1,208** ALD circuit blocks on the 34 base cards (**1,247** with Paper Tape; **1,310** with memory cable cards) |
| **Logic Cards in CCU (`Board B1`)** | **34 SLT/SLD cards** | **35** with Paper Tape option (`+1` card); **37** including 2 memory cable cards (`A5`, `A6`); **+3** for Search SER |
| **Distinct Card Types (`Board B1`)** | **24 logic card types** | **25** with Paper Tape option; **27** total part numbers in `LR010` Plug List |
| **Master Clock Frequency** | **240 kHz** | Crystal oscillator driving alternating `SSA` / `SSB` **2.4 µs** single-shots (`8.33 µs` cycle) |
| **Core Memory Capacity** | **2,048 bytes** | **1,024 16-bit words** (**16,384** general ferrite cores + **48** special-register cores) |
| **Core Memory Cycle Time** | **25 µs** | **33.3 µs** per `RWW` (P-only) nibble; **50 µs** per `RRWW` (P+Q) nibble (~8 µs intrinsic core speed) |
| **Word / Instruction Width** | **16 bits** | 2 bytes per word/instruction, processed **4 bits (1 nibble)** at a time |
| **Instruction Classes / Operations** | **8 classes / 24 ops** | Full Branch, Incremental Branch, Bit Control, Immediate SCAT, Register SCAT, Byte Indirect, Output, Input |
| **Working Registers** | **32 × 16-bit (`R0`–`R31`)** | Memory-mapped in the lowest **64 bytes** (`0x000`–`0x03F`) of core storage |
| **Factory Microprogram Size** | **1,584 bytes** | **792 16-bit instructions** (power-on reset always enters at byte **546** / `0x222`) |
| **Bootstrap Loader Size** | **3 instructions (6 bytes)** | Manually keyed into bytes `42`–`47` via the 12-key console panel |
| **Input / Output Speeds** | **20 cps in / 14.1 cps out** | 20 chars/sec Magnetic Tape Reader; 14.1 chars/sec (>120 WPM) Selectric Composer |

---

## 2. Logic Technology & Card Packaging (`Board B1`)

The Composer Control Unit (**CCU**, IBM Model 6320) is built on a single logic backplane (**Board `B1`**) paired with a magnetic core memory board (**Board `A1`**).

| Parameter | Value | Details |
|---|---|---|
| **Circuit Family** | **Low-Speed SLD** | Hybrid ceramic modules with discrete flip-chip silicon transistors/diodes and thick-film resistors |
| **Average Propagation Delay** | **~450 ns** | Per logic circuit |
| **Modules per Card** | **~17 modules** (avg.) | Functionally packaged; limited primarily by card edge pins |
| **Circuits per Module** | **~3 circuits** (avg.) | ~50 circuits per double-height card |
| **Signal Pins per Card Socket** | **44 pins** | **88 pins** per double-height card |
| **Distinct Module Schematics** | **69 circuit types** | Documented on ALD Card Circuits sheets `1401`–`1404` (`V` and `S` circuit families) |
| **Backplane Grid (`Board B1`)** | **7 rows × 13 columns** | Rows `1`–`7`, Columns `A`–`N` (skipping `I`) = **91 grid positions** (`65` card sockets used, `11` cable connectors, `15` unused sockets) |
| **Named Backplane Signals** | **~320 nets** | Indexed across the 8-page ALD Net Listing (`0501`–`0508`) |
| **Logic Diagram Sheets** | **13 ILDs / 56 ALDs** | Intermediate Logic Diagrams `0301`–`0313`; Automated Logic Diagrams `AA011`–`CC011` |

### Complete `Board B1` Card Inventory (`LR010` Socket Listing & Diagram `1201`)

| Card Type (580xxxx) | Form Factor | Qty | Socket Location(s) | Card Title / Function | ALD Blocks on Card |
|---|---|:---:|---|---|:---:|
| `5803859` | Single (1-socket) | **7** | `A4`, `A7`, `B5`, `B6`, `B7`, `C5`, `D5` | **GMS X-DIR / Y-DIR** (Core Memory X/Y Current Drivers & Switches) | 12 each (84 total) |
| `5806451` | Double (2-socket) | **2** | `F3/4`, `G3/4` | **DATA FLOW 1 & 2** (P-Buffer, 4-Bit Serial Adder/Subtractor, Bit Modify) | 61 / 58 (119 total) |
| `5806452` | Double (2-socket) | **2** | `K3/4`, `L3/4` | **LITES 1 & 2** (11 Console Lamp Multiplexers & Drivers) | 38 / 34 (72 total) |
| `5806472` | Double (2-socket) | **2** | `J1/2`, `K1/2` | **CPU INTEG & MT INTEG** (Contact Debounce & Signal Integrators) | 29 / 28 (57 total) |
| `5806476` | Double (2-socket) | **2** | `B1/2`, `C1/2` | **SENSE INH 1 & 2** (4 Sense Amplifiers, 4 Inhibit Drivers, Q-Buffer `1..4`) | 15 each (30 total) |
| `5806453` | Double (2-socket) | **1** | `J5/6` | **OUTPUT & OPL CDE REG** (`OUT 1..8` & `OPL CDE 1..7` Latches) | 67 |
| `5806454` | Double (2-socket) | **1** | `E3/4` | **WORD SEL** (Memory Allocation: `PSAR`, `A-Word`, `B-Word`, `Permute`) | 37 |
| `5806455` | Double (2-socket) | **1** | `L5/6` | **KEY ENCODE** (Console Keyboard Decode, Bit Sampling, CE Aid Decode) | 43 |
| `5806456` | Double (2-socket) | **1** | `H3/4` | **RD/WR CTL** (`WR ACC`, `WR P BFR`, `INH RD`, `SET 9`, Opcode Class Decode) | 61 |
| `5806457` | Double (2-socket) | **1** | `M3/4` | **CONSOLE** (Clock Stop Latches `STP CLK 1/2`, `KB SING ENT`, `LIGHTS` Latch) | 36 |
| `5806458` | Double (2-socket) | **1** | `G5/6` | **CLOCK** (`A`, `B`, `TWR`, `P TIME`, `CEN BT`, and Bit Time Counter `BT CTR A/B`) | 81 |
| `5806459` | Double (2-socket) | **1** | `F5/6` | **INSN TIME CTR** (Instruction Time Counter `I-0..I-9` & Steering Latches `A..D`) | 77 |
| `5806460` | Double (2-socket) | **1** | `H5/6` | **REG CTL** (`CAR OVER` Latch, `ADD`/`-ADD`, `LOAD Y`, `LOAD Q BFR 1/2`) | 34 |
| `5806461` | Double (2-socket) | **1** | `C6/7` | **MEM ADDR REG Y-SEL** (`Y1..Y3`, `Y7`, `Y11` Latches & Y-Driver/Switch Decodes) | 51 |
| `5806462` | Double (2-socket) | **1** | `D3/4` | **MEM ADDR REG X-SEL** (`Y4..Y6`, `Y8..Y10` Latches & X-Driver/Switch Decodes) | 74 |
| `5806463` | Double (2-socket) | **1** | `K5/6` | **OSC** (240 kHz Crystal Osc, `SSA`/`SSB`, `LOW` & `EQUAL` Latches, `TAKEN`) | 35 |
| `5806464` | Double (2-socket) | **1** | `J3/4` | **I/O CTL** (`MPX`, `EDIT`, `BUSY`, `INH`, `PRE INH`, `RDY` Latches & Strobes) | 50 |
| `5806465` | Double (2-socket) | **1** | `E5/6` | **VOLT REF & MEM PROTECT** (`START`, `MR`, `+10V` Ref, Power-Up/Down Protect) | 18 |
| `5806467` | Double (2-socket) | **1** | `L1/2` | **INTERFACE 1** (Translator `SYNC 1/2`, `DUMMY`, `OIF`, `TRANS SEL`, Single-Shots) | 42 |
| `5806468` | Double (2-socket) | **1** | `M1/2` | **INTERFACE 2** (Translator Selection Magnet Drivers `1..6, 8` & Cycle Clutch Driver) | 34 |
| `5806469` | Double (2-socket) | **1** | `F1/2` | **MT CTR** (Magnetic Tape 5-Bit Counter `MT CTR A..E`, Read & Set-Low Latches) | 60 |
| `5806470` | Double (2-socket) | **1** | `H1/2` | **MT MAG LOGIC** (Magnetic Tape Solenoid, Search, Detent & Clutch Control) | 55 |
| `5806471` | Double (2-socket) | **1** | `G1/2` | **MT DATA REG** (Magnetic Tape `DATA LATCH 1..9` & Odd X/Y/Z Parity Check) | 76 |
| `5806474` | Double (2-socket) | **1** | `D6/7` | **MEM CTL** (300 ns Strobe, Sense Amp Gate, `REWO`/`ROWE` & X/Y Current Feedback) | 15 |
| *`5806679`* | *Double (2-socket)* | ***1*** | *`M5/6`* | ***PAPER TAPE INTERFACE*** *(Optional Model 6354 PT Reader Latches `1..8` & Control)* | *39* |
| *`5805049`* | *Single (1-socket)* | ***1*** | *`A5`* | ***MEMORY CABLE SOCKET A5*** *(Core X/Y Drive & Switch Jumper/Resistor Network)* | *32* |
| *`5805050`* | *Single (1-socket)* | ***1*** | *`A6`* | ***MEMORY CABLE SOCKET A6*** *(Core X/Y Drive & Switch Jumper/Resistor Network)* | *31* |
| **Total** | **9 Single + 28 Double** | **37** | **65 sockets** | **27 distinct part numbers** (**34** base logic cards / **24** base logic types) | **1,310** |

> [!NOTE]
> **Other Cards in the MT/SC Ecosystem:**
> - **Model V MT/SR (Record-Only Input Station)**: Uses **7 SLD cards** mounted on a pivoting "Half Board" to replace the relay logic of earlier MT/ST models.
> - **Improved Search Feature (Special Engineering Request)**: Adds **3 SLT/SLD cards** to the CCU (with a board change) and **1 SMS card** to the Magnetic Tape Reader.

---

## 3. Clock, Timing & Execution Speed

| Timing Element | Value | Technical Details |
|---|---|---|
| **Crystal Oscillator (`S22DY`)** | **240 kHz** | Toggles binary trigger `FF` (`+OSC SS INPUT`) every **4.167 µs** |
| **Single-Shot Pulses (`SSA`, `SSB`)** | **2.4 µs** pulse width | Each fires once per **8.333 µs** oscillator period (180° out of phase, **1.77 µs** gap) |
| **Clock Counter Latches** | **17 latches** | `A`, `B` (2); `TWR`, `P TIME`, `CEN BT` (3); `BT CTR` + Steer (4); `INSN T CTR` + Steer (8) |
| **Core Sub-Cycle (Read or Write)** | **25 µs** | 3 oscillator cycles (`8.33 µs × 3`), vs. ~8 µs raw core capability |
| **P-Only Nibble Cycle (`RWW`)** | **33.3 µs** | Read P → Write Q → Write P (4 oscillator cycles = 8 `SSA`/`SSB` pulses) |
| **Full P+Q Nibble Cycle (`RRWW`)** | **50.0 µs** | Read P → Read Q → Write Q → Write P (6 oscillator cycles = 12 `SSA`/`SSB` pulses) |
| **Nibble Bit-Times per Word** | **4 Bit Times** | `BIT 1-4`, `BIT 5-8`, `BIT 9-12`, `BIT 13-16` (4 bits processed per Bit Time) |
| **P-Only Word Time (`I-0`, etc.)** | **133.3 µs** | 4 × `33.3 µs` `RWW` nibble cycles (`I-0` always runs `P ONLY`) |
| **Full P+Q Word Time (`I-1`, `I-9`, etc.)** | **200.0 µs** | 4 × `50.0 µs` `RRWW` nibble cycles |
| **Word Times per Instruction** | **2 to 10 Word Times** | Sequenced across `I-0` through `I-9`; unused states skipped by **Permute** logic (`ILD 0304`) |
| **Instruction Execution Time** | **333.3 µs – 1,533.3 µs** | **~650 to 3,000 instructions/sec** depending on instruction type and indirect addressing |

### Execution Timing by Instruction Class

| Instruction Class | Word Times Executed (`I-0`–`I-9`) | Total Word Times | Execution Time (µs) |
|---|---|:---:|:---:|
| **Branch (Not Taken)** | `I-0`, `I-1` | **2** | **333.3 µs** |
| **Branch (Taken, No Link)** | `I-0`, `I-1`, `I-9` | **3** | **533.3 µs** |
| **Branch with Link (`JMPL`)** | `I-0`, `I-1`, `I-2`, `I-3`, `I-9` | **5** | **866.7 µs** |
| **Incremental Branch (Not Taken)** | `I-0`, `I-1`, `I-2`, `I-3` | **4** | **666.7 µs** |
| **Incremental Branch (Taken)** | `I-0`, `I-1`, `I-2`, `I-3`, `I-9` | **5** | **866.7 µs** |
| **Bit Control (`TBIT`, `SBIT`, `RBIT`, `PBIT`)** | `I-0`, `I-1`, `I-2`, `I-3` | **4** | **666.7 µs** |
| **Immediate Arithmetic (`MOVI`, `ADDI`, `SUBI`, `CMPI`)** | `I-0`, `I-1`, `I-6`, `I-9` | **4** | **666.7 µs** |
| **Register Direct Arithmetic (`MOV`, `ADD`, `SUB`, `CMP`)** | `I-0`, `I-1`, `I-2`, `I-5`, `I-6`, `I-9` | **6** | **1,000.0 µs** (1.0 ms) |
| **Input / Output (Direct)** | `I-0`, `I-1`, `I-2`, `I-5` | **4** | **666.7 µs** |
| **Input / Output (Indirect / Multiplex `[R0]++`)** | `I-0`, `I-1`, `I-2`, `I-3`, `I-4`, `I-5` | **6** | **1,000.0 µs** (1.0 ms) |
| **Byte Indirect (Q-Indirect only)** | `I-0`, `I-1`, `I-2`, `I-3`, `I-4`, `I-5`, `I-6`, `I-9` | **8** | **1,266.7 µs** |
| **Byte Indirect (P-Indirect & Q-Indirect)** | `I-0`, `I-1`, `I-2`, `I-3`, `I-4`, `I-5`, `I-6`, `I-7`, `I-8`, `I-9` | **10** | **1,533.3 µs** |

---

## 4. Magnetic Core Memory (`Board A1`)

| Specification | Value | Notes |
|---|---|---|
| **Addressable Storage** | **2,048 bytes** | **1,024 16-bit words** (`0x000`–`0x7FF`), 11-bit byte address (`Y1`–`Y11`) |
| **Special Hardware Words in Core** | **3 words (6 bytes)** | `PSAR`/`IAW` (`Y DRV 8`), `A-Word` (`Y DRV 7`), `B-Word` (`Y DRV 6`) in `SEL MEM SPEC` area |
| **Total Ferrite Cores** | **16,384 + 48 cores** | `2,048 × 8 = 16,384` general bits + `3 × 16 = 48` special-register bits |
| **Array Configuration** | **3-D, 4-Plane (4-bit wide)** | 4 planes (`Plane 1..4`) read/written in parallel every nibble Bit Time |
| **X-Selection Matrix** | **64 X-lines** | **8 X-Drivers** (`X DRV 1..8`) × **8 X-Switches** (`X SW 1..8`) |
| **Y-Selection Matrix** | **32 Y-lines** | **8 Y-Drivers** (`Y DRV 1..8`) × **4 Y-Switches** (`Y SW 1..4`) |
| **Sense / Inhibit Windings** | **8 lines → 4 channels** | 4 Even (`REWO`) + 4 Odd (`ROWE`) lines feeding **4 Sense Amps** & **4 Inhibit Drivers** |
| **Memory Parity Bits** | **0** | No parity in core or data path; reliability ensured by 12V SLD margins and final copy proofreading |

### Standard Core Memory Allocation

| Region | Byte Count (1968 Paper) | Byte Range (1967 CE Manual) | Purpose |
|---|:---:|---|---|
| **Working Registers (`R0`–`R31`)** | **64 bytes** | `0`–`63` (`0x000`–`0x03F`) | 32 × 16-bit general-purpose registers |
| **Escapement Lookup Table** | **64 bytes** | `64`–`197` (`134` bytes) | Proportional character widths (`0`–`9` units) for 106 tape codes |
| **Other Tables & Quantities** | **60 bytes** | *(Included in `64`–`197`)* | Format parameters, constants, and state variables |
| **Output Block Buffer** | **120 bytes** | `198`–`315` (`118` bytes) | Composed line buffer for background Multiplex printing (`0xFF` halt) |
| **Input Block Buffer** | **150 bytes** | `316`–`465` (`150` bytes) | Raw character/code stream read from tape |
| **Microprogram Storage** | **1,584 bytes** | `466`–`2041` (`1,576` bytes) | **792 16-bit instructions**; hardware power-on entry at **byte 546 (`0x222`)** |
| **Special-Purpose Words** | **6 bytes** | `2042`–`2047` (logical) | `PSAR` (`IAW`), `A-Word` (`AW`), `B-Word` (`BW`) |
| **Total** | **2,048 bytes** | `0`–`2047` | **1,024 16-bit words** |

---

## 5. Processor Registers, Latches & Instruction Set

### Internal Hardware Latches (Non-Core State)

| Functional Group | Latch Count | Signal Names |
|---|:---:|---|
| **Clock & Timing Counter** | **17** | `OSC FF` (1), `A`, `B` (2), `TWR`, `P TIME`, `CEN BT` (3), `BT CTR A/B` + Steer (4), `INSN T CTR A..D` + Steer (8) *(18 with `OSC FF`)* |
| **Memory Address Latches (`MAL`)** | **11** | `Y1`–`Y11` (addresses 0–2047; `Y1` selects odd/upper byte) |
| **Data Path Buffers & Carry** | **9** | `Q BFR 1..4` (4), `P BFR 1..4` (4), `CAR OVER` (1) *(feeding 4-bit combinational `ACC 1..4`)* |
| **Operational Code Register** | **7** | `OPL CDE 1..7` |
| **Condition Code Latches** | **2** | `LOW` (`LO`), `EQUAL` (`EQ`) |
| **Output Register** | **8** | `OUT 1..8` (all 8 set = `0xFF` `HALT` end-of-multiplex character) |
| **I/O & Execution Control** | **9** | `START`, `MR`, `MPX`, `EDIT`, `BUSY`, `INH`, `PRE INH`, `RDY`, `LIGHTS` |
| **Keyboard & Translator Sync** | **7** | `KB SING ENT`, `LINE STOP`, `STP CLK 1/2`, `TSLTR SEL`, `SYNC 1/2`, `DUMMY`, `OIF` |
| **Magnetic Tape Reader Logic** | **17** | `MT CTR A..E` (5), `DATA LATCH 1..9` (9), `MT RD`, `MT SET LOW`, `TRF SCH STA` (3) |
| **Paper Tape Reader Option** | **9** | `PT DATA LATCH 1..8` (8), `RD CMND LATCH` (1) |

### Instruction Set & Static Microprogram Mix

All instructions are **16 bits** (1 word). Below are the **8 instruction classes** and their relative frequency in the standard factory microprogram (from Figure 3 of the 1968 *IBM J. Res. & Dev.* paper):

| Instruction Class | Microprogram Share | Distinct Variants | Register Addressing Scope | Description |
|---|:---:|:---:|---|---|
| **1. Branch** | **32.4%** | **6** | Any word address (`0..2046`, even) | `JMP`, `JMPL` (Jump & Link), `JMP.EQ`, `JMP.NE`, `JMP.LO`, `JMP.HI` |
| **2. Immediate (SCAT)** | **24.2%** | **4** | **16 even registers** (`R0, R2..R30`) | `SUBI`, `CMPI`, `ADDI`, `MOVI` with 8-bit literal (`0..255`) |
| **3. Register Direct (SCAT)** | **18.0%** | **4** | **All 32 registers** (`R0..R31`) | 16-bit two-address `SUB`, `CMP`, `ADD`, `MOV` ($P \text{ op } Q \to P$) |
| **4. Bit Control** | **9.9%** | **4** | **All 32 registers** (`R0..R31`), bits `0..15` | `RBIT` (Test & Reset), `TBIT` (Test), `SBIT` (Test & Set), `PBIT` (Test) |
| **5. Incremental Branch** | **8.6%** | **2** (× ±dir) | **4 registers** (`R0..R3`), bits `0..15` | `IJMP.ON`, `IJMP.OFF` relative jump by `±2..±30` bytes (`±1..±15` words) |
| **6. Byte (Indirect)** | **3.7%** | **2** (× modes) | **16 low registers** (`R0..R15`) | 8-bit `CMPB`, `MOVB` with P-indirect, Q-indirect, and `[Rn]++` (`+1`) indexing |
| **7. Output** | **2.0%** | **2** | **All 32 registers** (`R0..R31`; `R0` for `OUTM`) | `OUT` (single char, direct/indirect) and `OUTM` (cycle-stealing Multiplex via `[R0]++`) |
| **8. Input** | **1.2%** | **1** | **All 32 registers** (`R0..R31`) | `IN` single character from peripheral device (`TAPE_R`, `TAPE_L`, `PAPER`, `KB`) |
| **Total** | **100.0%** | **25** | **32 registers (`R0`–`R31`)** | **16-bit two-address microprogrammed word-processing ISA** |

---

## 6. Peripherals, Typography & Console Controls

| Subsystem / Metric | Value | Details |
|---|---|---|
| **Max Theoretical CCU I/O Rate** | **1,667 in / 833 out cps** | Bounded by instruction cycle time (`600 µs`–`1,200 µs`), far exceeding mechanical speeds |
| **Magnetic Tape Reader (6310)** | **2 stations, 20 cps** | Right station (Original tape) + Left station (Correction/Merge tape); 7-bit + odd parity |
| **Magnetic Tape Input Codes** | **106 valid codes** | **88 printing characters** (44 keys × lower/upper case) + control/format codes |
| **Paper Tape Reader (6354)** | **~14.8 cps (67.5 ms/char)** | Reads 6-channel TeleTypeSetter (TTS) or 8-channel paper tape; ~3 min media changeover |
| **Electromechanical Translator** | **Up to 16 cps (1/35 HP)** | **8 selection magnets** + **1 cycle clutch magnet** actuating **52 mechanical output links** |
| **Selectric Composer Printer** | **14.1 chars/sec** | **>120 words/min** (~25 newspaper lines/min); **2 photocells** for closed-loop sync (`23°`–`100°` pull zone) |
| **Proportional Escapement** | **3 to 9 units** | Character & interword widths; **1 unit** = **1/72"**, **1/84"**, or **1/96"** depending on pitch dial |
| **Composition Modes** | **5 modes** | `j` (Full Justify), `l` (Flush Left), `c` (Center), `r` (Flush Right), `d` (Dot Leaders) |
| **International Code Charts** | **5 language charts** | `0901` Domestic/UK, `0902` French-Latin, `0903` Latin, `0904` Germanic, `0905` Nordic |
| **Keyboard Efficiency** | **94.8% net useful** | 100 MT/SC input keystrokes yield **94.8** output operations (vs. **74.8%** hot-zone paper tape, **39.4%** manual Composer) |
| **Economic Crossover Volume** | **44,000 chars/day** | Output volume where MT/SC becomes cheaper per character than a manual Selectric Composer (**100,000 chars/day** per input keyboard capacity) |
| **Console Control Panel** | **12 keys, 11 lights** | Plus **2 5-position CE rotary switches** exposing memory editing, 5 stepping granularities, and **50 internal signals** |

---

## 7. Physical & Electrical Specifications

| Unit / Parameter | Value | Notes |
|---|---|---|
| **Composer Console Weight** | **207 lbs / 94 kg** (233 lbs / 106 kg crated) | Houses CCU logic gate, core memory, power supply, and mechanical Translator |
| **Magnetic Tape Reader Weight** | **156 lbs / 71 kg** (196 lbs / 89 kg crated) | Dual-station Model 6310 reader cabinet |
| **Modified Selectric Composer** | **65 lbs / 29.5 kg** (65 lbs / 29.5 kg crated) | Mounted atop the Composer Console above the Translator |
| **Total Playback Station Weight** | **428 lbs / 194 kg** (494 lbs / 224 kg crated) | Combined Console + MT Reader + Selectric Composer |
| **MT/SR Recording Station Weight** | **521 lbs / 236 kg** (565 lbs / 256 kg crated) | MT/SR Console (`226 lbs / 102.5 kg`) + Selectric I/O (`70 lbs / 32 kg`) + Desk (`225 lbs / 102 kg`) |
| **AC Power Input** | **115 VAC, 60 Hz** | **5.0 A** for MT/SC or PT/SC (**6.2 A** with both readers; **1.2 A** Composer alone; **2.5 A** MT/SR) |
| **Internal DC Power Rails** | **+12V, -12V, +48V, +10V** | **+12V ±7%** (4A fuse, 70,000 µF cap) for SLD logic & core; **-12V** (0.5A, 5,000 µF); **+48V** (2A, 8,000 µF) for magnets/solenoids |
