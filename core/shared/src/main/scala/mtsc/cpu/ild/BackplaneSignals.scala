package mtsc.cpu.ild

import mtsc.console.{CeEditSelector, CeStepGranularity}
import mtsc.isa.Decoder
import mtsc.types.Word

/**
 * Hardware backplane signal bus and latch state for the IBM MT/SC 6320 Composer Control Unit (CCU),
 * following the 1967 Maintenance Diagram Manual Intermediate Logic Diagrams (ILDs `0301`–`0313`)
 * and Automated Logic Diagrams (ALDs `AA011`–`AA273`).
 *
 * All signals are represented in positive logic (`true` = asserted / active according to the
 * signal's base name; active-low backplane lines such as `-PERMUTE` or `-P TIME*` are provided
 * as explicit helper methods or fields with `not` prefixes).
 */
final class BackplaneSignals {

  // ===============================================================================================
  // External / Console Switch Inputs (AA151, AA221)
  // ===============================================================================================

  /** `+MASTER RESET` (AA151): Power-on or console master reset pulse. */
  var masterReset: Boolean = false

  /** CE Console 5-position Clock Control Switch (`Auto`, `Jump`, `ProgramStep`, `Word`, `Bit`, AA221). */
  var clockSwitch: CeStepGranularity = CeStepGranularity.Auto

  /** CE Console `STEP` pushbutton depressed (`-STEP BUT` active low on AA221 / 0303). */
  var stepButtonPressed: Boolean = false

  /** CE Console 5-position Edit Memory Selector Switch (`Proc`, `Edit`, `Addr`, `Data`, `Prog`, AA221 / 0312). */
  var editSwitch: CeEditSelector = CeEditSelector.Proc

  /** `+INHIBIT MEMORY` (AA052): Disables Current Source X and Y logic when true. */
  var inhibitMemory: Boolean = false

  /** `+SELECT MEMORY` (AA112): Enables Current Source X and Y logic for valid memory addresses. */
  var selectMemory: Boolean = true

  // ===============================================================================================
  // ILD 0303: Oscillator, Clock, Bit Time Counter, Start & Stop Logic (AA071, AA081, AA082, AA083, AA131)
  // ===============================================================================================

  /** Oscillator binary trigger `FF` (`+OSC SS INPUT`, AA131 5B-AE): toggles every 4.167 us (240 kHz). */
  var oscTrigger: Boolean = false

  /** `+SINGLE SHOT A` (AA131 6B-AF): 2.4 us pulse fired when `oscTrigger` flips to the A half-cycle. */
  var ssa: Boolean = false

  /** `+SINGLE SHOT B` (AA131 6D-AG / 7D-AH): 2.4 us pulse fired when `oscTrigger` flips to the B half-cycle. */
  var ssb: Boolean = false

  /** `+STOP CLOCK 1` latch (`STP CLK 1`, AA071 5D-AK): When true, inhibits oscillator trigger `FF`. */
  var stpClk1: Boolean = false

  /** `+STOP CLOCK 2` latch (`STP CLK 2`, AA071 5K-AT): Single-step pushbutton interlock latch. */
  var stpClk2: Boolean = false

  /** `+A` quadrature clock latch (`A LATCH`, AA081 4B-AD). */
  var a: Boolean = true

  /** `+B` quadrature clock latch (`B LATCH`, AA081 4E-AT). */
  var b: Boolean = true

  /** `+TIME WRITE` trinary clock latch (`TWR`, AA081 3J-BG). */
  var twr: Boolean = false

  /** `+P TIME` trinary clock latch (`P TIME`, AA081 7B-AK). */
  var pTime: Boolean = true

  /** `+CENTRAL BIT TIME` trinary clock latch (`CEN BT`, AA081 7L-BQ). */
  var cenBt: Boolean = false

  /** `+STR BIT TIME CTR A` steering latch (AA082 7E-AN). */
  var strBtCtrA: Boolean = false

  /** `+STR BIT TIME CTR B` steering latch (AA082 5L-BE). */
  var strBtCtrB: Boolean = false

  /** `+BIT TIME CTR A` indicator latch (AA082 5B-AC). */
  var btCtrA: Boolean = false

  /** `+BIT TIME CTR B` indicator latch (AA082 5H-AV). */
  var btCtrB: Boolean = false

  // --- Combinational Clock & Bit Time Nets (0303) ---

  /** `+AUTO OR STEP` (AA071 / AA221): True when Clock Switch is in `Auto` or `STEP` button is pressed. */
  var autoOrStep: Boolean = true

  /** `-STEP BUT` / `+STEP BUTTON` (AA071): True when `STEP` button is NOT pressed. */
  var stepButtonReleased: Boolean = true

  /** `+STEP 1` and `-STEP 1` (AA071 / AA221 top wafer): `+STEP 1` is true in `ProgramStep` and `Bit`. */
  var step1: Boolean = false
  var notStep1: Boolean = false

  /** `+STEP 2` and `-STEP 2` (AA071 / AA221 middle wafer): `+STEP 2` is true in `Word` and `Bit`. */
  var step2: Boolean = false
  var notStep2: Boolean = false

  /** `-P TIME*` (AA081 5B-AH, pin AJ4): `!(pTime && !cenBt)`. Inhibits CS X/Y during indicator load interval. */
  var notPTimeStar: Boolean = false

  /** `-B AND -TIME WRITE` (AA083 AN4): `!b && !twr`. */
  var notBAndNotTwr: Boolean = false

  /** `+BUMP STROBE TIME` (`BMP STRB T`, AA081 7H-BD): `!a && b && cenBt`. */
  var bmpStrbT: Boolean = false

  /** `+STEER LATCH STROBE` (`STR LTH STROBE`, AA081 6F-AX): `!a && !b && twr`. */
  var strLthStrobe: Boolean = false

  /** `+PERMUTE TIME` (`PRMT TIME`, AA083): `b13_16 && !b && twr` (`-PERMUTE TIME` is `!prmtTime`). */
  var prmtTime: Boolean = false

  /** `+RESET INDICATORS` (`RES INDS`, AA082 2N-BH / AA092 9L-BE): `ssa && b && pTime && !cenBt`. */
  var resInds: Boolean = false

  /** `+LOAD INDICATORS` (`LD INDS`, AA083 AE4): `!a && b && pTime`. */
  var ldInds: Boolean = false

  /** Decoded Bit Times (`+BIT 1-4`, `+BIT 5-8` on AA081; `+BIT 9-12`, `+BIT 13-16` on AA083). */
  var b1_4: Boolean = true
  var b5_8: Boolean = false
  var b9_12: Boolean = false
  var b13_16: Boolean = false

  /** `+CURRENT SOURCE X LOGIC` (`CS X LOG`, AA082 3L-BC). */
  var csXLog: Boolean = false

  /** `+CURRENT SOURCE Y LOGIC` (`CS Y LOG`, AA082 3D-BJ). */
  var csYLog: Boolean = false

  /** `REWO` (Read Even / Write Odd, Timing Diagram 0601 / AA023). */
  var rewo: Boolean = false

  /** `ROWE` (Read Odd / Write Even, Timing Diagram 0601 / AA023). */
  var rowe: Boolean = false

  // ===============================================================================================
  // ILD 0304: Permute Logic & Instruction Time Counter (AA041, AA061, AA091, AA092, AA131)
  // ===============================================================================================

  /**
   * Operational Code Latches `OPL CDE 1..7` (AA032 / ILD 0309):
   * - `oplCde1` = instruction bit 15 (MSB)
   * - `oplCde2` = instruction bit 14
   * - `oplCde3` = instruction bit 13
   * - `oplCde4` = instruction bit 12
   * - `oplCde5` = instruction bit 11
   * - `oplCde6` = instruction bit 6
   * - `oplCde7` = instruction bit 0 (LSB)
   */
  var oplCde1: Boolean = false
  var oplCde2: Boolean = false
  var oplCde3: Boolean = false
  var oplCde4: Boolean = false
  var oplCde5: Boolean = false
  var oplCde6: Boolean = false
  var oplCde7: Boolean = false

  /** `+LOW LATCH` (AA132 4B-AD / ILD 0309). */
  var low: Boolean = false

  /** `+EQUAL LATCH` (AA132 4L-AP / ILD 0309). */
  var eq: Boolean = false

  /** `+EDIT` latch (AA141 / ILD 0312): True when CE Edit Switch is not in `Proc`. */
  var edit: Boolean = false

  /** Steering Instruction Time Counter latches `ST INST TIME CTR A..D` (AA091). */
  var strInsnTCtrA: Boolean = false
  var strInsnTCtrB: Boolean = false
  var strInsnTCtrC: Boolean = false
  var strInsnTCtrD: Boolean = false

  /** Indicator Instruction Time Counter latches `INST TIME CTR A..D` (AA091). */
  var insnTCtrA: Boolean = false
  var insnTCtrB: Boolean = false
  var insnTCtrC: Boolean = false
  var insnTCtrD: Boolean = false

  // --- Combinational Instruction Decode & Permute Nets (0304) ---

  /** Decoded Instruction Times `INSN T 0..9` (AA092). */
  var insnT0: Boolean = true
  var insnT1: Boolean = false
  var insnT2: Boolean = false
  var insnT3: Boolean = false
  var insnT4: Boolean = false
  var insnT5: Boolean = false
  var insnT6: Boolean = false
  var insnT7: Boolean = false
  var insnT8: Boolean = false
  var insnT9: Boolean = false

  /** `+ARITHMETIC OPERATION` (`ATH OP`, AA041 AP4): `oplCde1 || oplCde2`. */
  var athOp: Boolean = false

  /** `+IMMEDIATE` (AA041 AS4 inverted): `oplCde1 && oplCde2`. */
  var immediate: Boolean = false

  /** `+OUTPUT` (AA061 AM4 inverted / AA132 AL4): `!athOp && !oplCde3 && oplCde4 && !oplCde5 && !oplCde7`. */
  var output: Boolean = false

  /** `+INPUT` (AA061 AA4 inverted / AA092 BL4): `!athOp && !oplCde3 && oplCde4 && !oplCde7 && !output`. */
  var input: Boolean = false

  /** `+PROGRAM CONTROL` (`PRG CTL`, AA041 AT4 / AA061 AY4): `!athOp && oplCde3 && oplCde4 && oplCde7`. */
  var prgCtl: Boolean = false

  /** `+JUMP` (`JMP`, AA041 AL4): `!athOp && !input && !output && !prgCtl`. */
  var jmp: Boolean = true

  /** `+TAKEN` (`TKN`, AA131 AS4): Branch / Incremental Jump condition satisfied. */
  var tkn: Boolean = false

  /** `+SET 9` (AA061 BC4): Requests permute to `I-9` (`1001`). */
  var set9: Boolean = false

  /** `+PERMUTE` (`PRMT`, AA041 AV4 inverted): True when Instruction Time Counter should permute rather than increment. */
  var prmt: Boolean = false

  /** `+PERMUTE I TIME STEER LATCHES` (`PRMT INSN T STR LTH`, AA092 BB4): `prmtTime && prmt`. */
  var prmtInsnTStrLth: Boolean = false

  /** `+RES INSN T STR LTH` (`-RESET I-TIME STEER LATCHES` inverted, AA092 BG4): `strLthStrobe && prmtInsnTStrLth`. */
  var resInsnTStrLth: Boolean = false

  /** `+P ONLY` (AA092 CS4): `!insnT1 && !insnT9 && (!jmp || !insnT3)`. Selects 33.3 us P-Only bit cycle vs 50.0 us P&Q cycle. */
  var pOnly: Boolean = true

  /** `+BEGINNING PROGRAM STEP` (AA092 BE4): `insnT0 && b1_4 && pTime && !cenBt`. */
  var begPrgStp: Boolean = false

    // ===============================================================================================
  // ILD 0305: Power, Start, Sense Amplifiers, Read Inhibit & Q-Buffer (AA052, AA061, AA083, AA101, AA121, AA151, AA241, AA261, AA262)
  // ===============================================================================================

  /** `+POWER ON` (AA151 AS4): DC power good indicator. */
  var pwrOn: Boolean = true

  /** `+START` latch (AA151 3G-AK): Run enable latch set after power-on reset at `BEG P.S.`. */
  var start: Boolean = true

  /** CE Console `CLEAR` pushbutton depressed (`+CLR` on AA151 / 0305 / 0312). */
  var clrButtonPressed: Boolean = false

  /** CE Console Display selector lines (`+DPLY 1` = Edit/Addr, `+DPLY 2` = Data/Prog on AA221 / 0312). */
  var dply1: Boolean = false
  var dply2: Boolean = false

  /** `+INHIBIT` (`INH`, AA142 AF4 / ILD 0311): I/O wait / stall or multiplex interrupt inhibit latch. */
  var inh: Boolean = false

  /** `+MULTIPLEX` (`MPX`, AA141 / ILD 0311): Background Translator multiplex output mode latch. */
  var mpx: Boolean = false

  /** `+READY` (`RDY`, AA142 BC4 / ILD 0311): Selected I/O or Translator device ready signal. */
  var rdy: Boolean = true

  /** `+TRANS + COMP` (AA041 / AA061): Transfer or Compare decode (`oplCde2 && (oplCde3 || oplCde4 || oplCde6 || oplCde7)`). */
  var transOrComp: Boolean = false

  /** `16-bit Transfer` (`MOV` / `MOVI`, AA041): `oplCde2 && oplCde3 && oplCde4 && !oplCde6 && !oplCde7`. */
  var trans16: Boolean = false

  /** `+INHIBIT READ` (`INH RD`, AA061 AG4 / AA101): Suppresses Sense Amplifier outputs during destructive clear reads. */
  var inhRd: Boolean = false

  /** Upper-byte `CS Y LOG` suppression (`AA082 1C-AE, 1D-AG`): suppresses Y current during `B9-12` and `B13-16` for 8-bit operations. */
  var killCsYUpperByte: Boolean = false

  /** `+READ EVEN WRITE ODD` (`REWO`, AA121 BS4) and `+READ ODD WRITE EVEN` (`ROWE`, AA121 AY4). */
  var rewoGate: Boolean = false
  var roweGate: Boolean = false

  /** `+READ EVEN WRITE ODD GATE` (`AA241 AQ4`) and `+READ ODD WRITE EVEN GATE` (`AA241 AS4`). */
  var rewoSenseGate: Boolean = false
  var roweSenseGate: Boolean = false

  /** `+READ GATE` (`AA241 AP4`): Active during core read when `csYLog && !twr`. */
  var readGate: Boolean = false

  /** Sense Amplifier outputs `SENSE AMP 1..4` (`AA261`, `AA262`). */
  var senseAmp1: Boolean = false
  var senseAmp2: Boolean = false
  var senseAmp3: Boolean = false
  var senseAmp4: Boolean = false

  /** `-RESET BUFFER` (`RES BFR` in positive logic, AA083 AJ4): `(!ssa && !b && !twr && pTime) || masterReset`. */
  var resBfr: Boolean = false

  /** `+LOAD Q BUFFER 1` (`LD Q BFR 1`, AA101 AF4): Adds `+1` into `Q BFR 1` at `B1-4, BMP STRB T`. */
  var ldQBfr1: Boolean = false

  /** `+LOAD Q BUFFER 2` (`LD Q BFR 2`, AA101 AY4): Adds `+2` into `Q BFR 2` at `B1-4, BMP STRB T` (or sets power-on PSAR on MR). */
  var ldQBfr2: Boolean = false

  /** `Q BUFFER 1..4` Sense Amplifier Triggers (`+Q BUFFER 1..4`, AA261 / AA262). */
  var qBfr1: Boolean = false
  var qBfr2: Boolean = false
  var qBfr3: Boolean = false
  var qBfr4: Boolean = false

  // ===============================================================================================
  // ILD 0306 & 0307: Memory Address Latches Y1..Y11 & X/Y Current Decoders (AA042, AA101, AA111, AA112, AA121, AA122, AA161)
  // ===============================================================================================

  /** `+SELECT PROGRAM STEP ADDRESS` (`SEL PROG STEP ADDR`, AA042 AR4). */
  var selProgStepAddr: Boolean = true

  /** `+SELECT A WORD` (`SEL A WD`, AA042 AT4). */
  var selAWd: Boolean = false

  /** `+SELECT B WORD` (`SEL B WD`, AA042 BA4). */
  var selBWd: Boolean = false

  /** `+SELECT MEMORY SPECIAL` (`SEL MEM SPEC`, AA112): `selProgStepAddr || selAWd || selBWd`. */
  var selMemSpec: Boolean = true

  /** `+SELECT MEMORY UPPER` (`SEL MEM UP`, AA042 AF4): True when addressing all 11 bits `Y1..Y11` vs working registers `Y2..Y6`. */
  var selMemUp: Boolean = false

  /** `+LOAD Y` (`LD Y`, AA101): `!insnTCtrA && !insnT6 && cenBt` (`I-0, I-2, I-4, I-8` at `CEN BT`). */
  var ldY: Boolean = false

  /** `+LOAD Y INSN T 6` (`AA101`): `insnT6 && cenBt`. */
  var ldYInsnT6: Boolean = false

  /** `+INCR JUMP RESET Y` (`AA101`): `pTime && b5_8 && oplCde1 && insnT2`. */
  var incrJmpResY: Boolean = false

  /** `-RESET Y` (`RES Y` in positive logic, AA101 BG4): Resets `Y1..Y11` prior to loading in even I-times or during MPX I-3. */
  var resY: Boolean = false

  /** 11-bit Memory Address Latches `Y1..Y11` (weights `1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024`, AA111 / AA161). */
  var y1: Boolean = false
  var y2: Boolean = false
  var y3: Boolean = false
  var y4: Boolean = false
  var y5: Boolean = false
  var y6: Boolean = false
  var y7: Boolean = false
  var y8: Boolean = false
  var y9: Boolean = false
  var y10: Boolean = false
  var y11: Boolean = false

  /** Intermediate X/Y Decode lines (`AA112`, `AA121`, `AA122`). */
  var dcdDrvrY1234: Boolean = false
  var dcdDrvrY2468: Boolean = true
  var dcdDrvrY3478: Boolean = true
  var dcdDrvrY5678: Boolean = true
  var dcdSwY24: Boolean = false
  var dcdSwY34: Boolean = true

  var dcdDrvrX1: Boolean = false
  var dcdDrvrX234: Boolean = false
  var dcdDrvrX567: Boolean = false
  var dcdDrvrX8: Boolean = true
  var dcdSwX1234: Boolean = false
  var dcdSwX56: Boolean = false
  var dcdSwX57: Boolean = false
  var dcdSwX8: Boolean = true

  /** Selected X-Driver (`0..7` for `X DRV 1..8`), X-Switch (`0..7` for `X SW 1..8`), Y-Driver (`0..7` for `Y DRV 1..8`), Y-Switch (`0..3` for `Y SW 1..4`). */
  var activeXDriver: Int = 7
  var activeXSwitch: Int = 7
  var activeYDriver: Int = 7
  var activeYSwitch: Int = 2

  // ===============================================================================================
  // ILD 0308: P-Buffer, Carry Over Latch & 4-Bit Serial Adder/Subtractor (AA011..AA014, AA051, AA083, AA101)
  // ===============================================================================================

  /** External Input Bit lines `IN BIT 1..8` from Tape / Paper Tape Reader (`AA141` / `0308`). */
  var inBit1: Boolean = false
  var inBit2: Boolean = false
  var inBit3: Boolean = false
  var inBit4: Boolean = false
  var inBit5: Boolean = false
  var inBit6: Boolean = false
  var inBit7: Boolean = false
  var inBit8: Boolean = false

  /** Control Panel Keyboard Code lines `KB CODE A..D`, `KB CODE RDY`, `LN STP LTH` (`AA051` / `0308` / `0312`). */
  var kbCodeA: Boolean = false
  var kbCodeB: Boolean = false
  var kbCodeC: Boolean = false
  var kbCodeD: Boolean = false
  var kbCodeRdy: Boolean = false
  var lnStpLth: Boolean = false

  /** `+KEYBOARD CODE` (`KB CDE`, AA121 AQ4): `y8 && y9 && y10` (Device `0111`). */
  var kbCde: Boolean = false

  /** `+INPUT STROBE` (`IN STRB`, AA141 AW4): `cenBt && insnT5 && !inh && !kbCde && input`. */
  var inStrb: Boolean = false

  /** `+KEYBOARD STROBE` (`KB STRB`, AA141 BC4) and `+KEYBOARD ENTRY STROBE` (`KB ENT STRB`, AA051). */
  var kbStrb: Boolean = false
  var kbEntStrb: Boolean = false

  /** `RES P BFR` (`AA083` / `AA011`): `(resBfr && !cenBt) || masterReset`. */
  var resPBfr: Boolean = false

  /** `P BUFFER 1..4` Latches (`+P BUFFER 1..4`, AA011 / AA013). */
  var pBfr1: Boolean = false
  var pBfr2: Boolean = false
  var pBfr3: Boolean = false
  var pBfr4: Boolean = false

  /** `+ADD` and `-ADD` (`AA101`): `add = !(insnT9 && athOp && !oplCde3)`. */
  var add: Boolean = true
  var notAdd: Boolean = false

  /** Steering and Indicator latches for `+CARRY OVER LATCH` (`CAR OVER`, AA101 4G-AT..7G-BA). */
  var strCarOver: Boolean = false
  var carOver: Boolean = false

  /** Ripple Carry / Borrow outputs `+CARRY 1..4` (`AA011`, `AA013`). */
  var car1: Boolean = false
  var car2: Boolean = false
  var car3: Boolean = false
  var car4: Boolean = false

  /** 4-Bit Accumulator outputs `+ACCUMULATOR 1..4` (`AA011`..`AA014`). */
  var acc1: Boolean = false
  var acc2: Boolean = false
  var acc3: Boolean = false
  var acc4: Boolean = false

  /** `+ACCUMULATOR EQUAL ZERO` (`AA012 AW4`) and `-ACCUMULATOR EQUAL ZERO` (`AA131 AX4`). */
  var accEqualZero: Boolean = true

  // ===============================================================================================
  // ILD 0309: Operational Code Latches, Bit Sampling & LOW / EQUAL Latches (AA012, AA032, AA051, AA072, AA101, AA132)
  // ===============================================================================================

  /** `+LOAD OPERATIONAL CODE` (`LOAD OPL CDE`, AA101): `(!mpx || !rdy) && cenBt && insnT1`. */
  var loadOplCde: Boolean = false

  /** `+SAMPLED BIT TIME` (`SAM BT`, AA051 AE4): `(prgCtl || oplCde1) && (y11 == btCtrB) && (y10 == btCtrA)`. */
  var samBt: Boolean = false

  /** `+SAMPLED BIT POSITION` (`SAM B POS`, AA012 AZ4): True during `samBt` when the selected `ACC` bit is `0`. */
  var samBPos: Boolean = false

  /** `-RESET LOW AND EQUAL` (`RES LO & EQ` in positive logic, AA132 AK4): Presets `EQ = true, LOW = false` at `I-2, I-4, I-6, I-8`. */
  var resLoAndEq: Boolean = false

  /** External `-SET LOW` (`SET LO` in positive logic, AA193 AP4): Tape reader parity error / skip indicator. */
  var setLo: Boolean = false

  // ===============================================================================================
  // ILD 0310: Write Controls, Current Source Logic & Sense Inhibit Lines (AA012, AA014, AA041, AA052, AA061, AA121, BA011)
  // ===============================================================================================

  /** `+WRITE P BUFFER` (`WR P BFR`, AA061 AU4). */
  var wrPBfr: Boolean = false

  /** `+WRITE ACCUMULATOR` (`WR ACC`, AA061 AJ4). */
  var wrAcc: Boolean = false

  /** `+EDIT PROGRAM CONTROL BIT` (`EDIT PRG CTL BIT`, AA041 AN4): `insnT3 && samBt && !oplCde6 && prgCtl`. */
  var editPrgCtlBit: Boolean = false

  /** `-EDIT BIT 1..4` (`EDIT BIT 1..4` in positive logic, AA012 / AA014). */
  var editBit1: Boolean = false
  var editBit2: Boolean = false
  var editBit3: Boolean = false
  var editBit4: Boolean = false

  /** Data bits `1..4` to be written to Core Memory Planes `1..4` during `TWR` (`true` = write `1`, `false` = inhibit / write `0`). */
  var writeBit1: Boolean = false
  var writeBit2: Boolean = false
  var writeBit3: Boolean = false
  var writeBit4: Boolean = false

  /** `+CURRENT SOURCE 1..4` (`CURR SOURCE 1..4`, AA012 / AA014): Inhibit driver enable (`twr && !inhibitMemory && !writeBit(i)`). */
  var currSource1: Boolean = false
  var currSource2: Boolean = false
  var currSource3: Boolean = false
  var currSource4: Boolean = false

  /** `SENSE INH LINE EV 1..4` and `SENSE INH LINE ODD 1..4` (`BA011`). */
  var senseInhLineEv1: Boolean = false
  var senseInhLineEv2: Boolean = false
  var senseInhLineEv3: Boolean = false
  var senseInhLineEv4: Boolean = false
  var senseInhLineOdd1: Boolean = false
  var senseInhLineOdd2: Boolean = false
  var senseInhLineOdd3: Boolean = false
  var senseInhLineOdd4: Boolean = false

  // ===============================================================================================
  // ILD 0311: Translator Output Controls & Output Latches (AA031, AA072, AA141, AA142, AA171, AA172, AA181, AA233)
  // ===============================================================================================

  /** `OUTPUT 1..8` Latches (`+OUTPUT 1..8`, AA031 / ILD 0311): 8-bit Output Register. */
  var out1: Boolean = false
  var out2: Boolean = false
  var out3: Boolean = false
  var out4: Boolean = false
  var out5: Boolean = false
  var out6: Boolean = false
  var out7: Boolean = false
  var out8: Boolean = false

  /** `+LOAD OUTPUT LATCH` (`LD OUT LTH`, AA141 AQ4). */
  var ldOutLth: Boolean = false

  /** `-RESET OUTPUT LATCH` (`RES OUT LTH` in positive logic, AA142 / AA031). */
  var resOutLth: Boolean = false

  /** `-HALT` (`halt` in positive logic, AA031 7G-AT): `out1 && out2 && out3 && out4 && out5 && out6 && out7 && out8` (`0xFF`). */
  var halt: Boolean = false

  /** `+KEYBOARD SINGLE ENTRY` (`KB SNG ENT`, AA051 / 0308 / 0311). */
  var kbSingleEntry: Boolean = false

  // ===============================================================================================
  // ILD 0301 & 0302: Magnetic Tape Deck Magnet/Solenoid Logic, MT Counter, Data Latches & Parity
  // (AA191, AA192, AA193, AA201, AA202, AA211, AA212, AA222)
  // ===============================================================================================

  /** 5-bit Magnetic Tape Counter flip-flops `MT CTR A..E` (`AA191`, `AA192` / `ILD 0302`). Initialized to `0 Count` (`00010`). */
  var mtCtrA: Boolean = false
  var mtCtrB: Boolean = false
  var mtCtrC: Boolean = false
  var mtCtrD: Boolean = true
  var mtCtrE: Boolean = false

  /** Emitter sweep single-shot pulses `MPS A` (odd emitter coils) and `MPS B` (even emitter coils) (`AA201` / `ILD 0302`). */
  var mpsA: Boolean = false
  var mpsB: Boolean = false

  /** Counter preset and reset buses (`ILD 0302`): `RD COM` (`AA072`), `SET 0` (`AA191`), `SET 14` (`AA192`), `SET 15` (`AA202`), `COM RES` (`AA202`). */
  var rdCom: Boolean = false
  var mtSet0: Boolean = false
  var mtSet14: Boolean = false
  var mtSet15: Boolean = false
  var mtComRes: Boolean = false

  /** Decoded `MT CTR A..E` count states (`Count 0`..`Count 10`, `13 Count`, `14 Count`, `15 Count` on `ILD 0301` & `0302`). */
  var mtCount0: Boolean = true
  var mtCount1: Boolean = false
  var mtCount2: Boolean = false
  var mtCount3: Boolean = false
  var mtCount4: Boolean = false
  var mtCount5: Boolean = false
  var mtCount6: Boolean = false
  var mtCount7: Boolean = false
  var mtCount8: Boolean = false
  var mtCount9: Boolean = false
  var mtTenCount: Boolean = false
  var mtCount13: Boolean = false
  var mtCount14: Boolean = false
  var mtCount15: Boolean = false

  /** 9-bit Magnetic Tape Data Register flip-flops `DATA LATCH 1..9` (`AA211` / `ILD 0302`). */
  var mtData1: Boolean = false
  var mtData2: Boolean = false
  var mtData3: Boolean = false
  var mtData4: Boolean = false
  var mtData5: Boolean = false
  var mtData6: Boolean = false
  var mtData7: Boolean = false
  var mtData8: Boolean = false
  var mtData9: Boolean = false

  /** Read head sense amplifier pulse `MT BIT` (`AA193`), search bit sense `SCH BIT` (`AA193`), and `RES DL3` (`AA211`). */
  var mtBit: Boolean = false
  var mtSchBit: Boolean = false
  var mtResDl3: Boolean = false

  /** 3-Group Odd Parity Checker signals `X PRTY` (`DL 1..3`), `Y PRTY` (`DL 4..6`), `Z PRTY` (`DL 7..9`), and `ODD` (`AA212` / `ILD 0302`). */
  var mtXParity: Boolean = false
  var mtYParity: Boolean = false
  var mtZParity: Boolean = false
  var mtOddParity: Boolean = false

  /** Station selection, Transfer Search Station, Read, Search, Increment, and Carrier Return decode (`ILD 0301`). */
  var mtStaRight: Boolean = true
  var mtStaLeft: Boolean = false
  var mtCde: Boolean = false
  var mtSch: Boolean = false
  var mtRd: Boolean = false
  var trSchSta: Boolean = false
  var mtIncr: Boolean = false
  var mtCarRet: Boolean = false

  /** Magnetic Tape transport solenoid, clutch, and magnet driver logic (`AA201`, `AA202` / `ILD 0301`). */
  var mtCycClutch: Boolean = false
  var mtSchPosMag: Boolean = false
  var mtLdSchSol: Boolean = false
  var mtStepMags: Boolean = false
  var mtDntRtSol: Boolean = false
  var mtDntLfSol: Boolean = false
  var mtLfPpSol: Boolean = false
  var mtHiBias: Boolean = false

  /** Magnetic Tape Reader Console pushbuttons and mechanical switch contacts (`BC051`, `AA222` / `ILD 0301` & `0302`). */
  var mtLdRt: Boolean = false
  var mtLdLf: Boolean = false
  var mtUnldRt: Boolean = false
  var mtUnldLf: Boolean = false
  var mtSchKey: Boolean = false
  var mtReread: Boolean = false
  var mtSkip: Boolean = false
  var mtRtPpUp: Boolean = false
  var mtLfPpUp: Boolean = false
  var mtLdrSenLfEmpty: Boolean = true
  var mtRwdSen: Boolean = false
  var mtDntOut: Boolean = false
  var mtSchPos: Boolean = false
  var mtHome: Boolean = true
  var mtEotOrUnld: Boolean = false
  var mtLdSchSolSw: Boolean = false

  /** Paper Tape & Combined Character Readiness (`AA232`, `AA233`, `AA212` / `0311`). */
  var ptCharRdy: Boolean = false
  var charRdy: Boolean = false

  /** `+UPSET PREINHIBIT` (`UPSET PRE INH`, AA141): `!b && twr && b13_16 && insnT1` (`prmtTime && insnT1`). */
  var upsetPreInh: Boolean = false

  /** `+PREINHIBIT` Latch (`PRE INH`, AA142). */
  var preInh: Boolean = false

  /** `+SELECT OUTPUT DEVICE` (`SEL OUT DEV`, AA142 AN4): `cenBt && insnT2 && output && rdy && !mpx`. */
  var selOutDev: Boolean = false

  /** `+DESELECT OUTPUT DEVICE` (`DESEL OUT DEV`, AA072 AS4): `selOutDev && b`. */
  var deselOutDev: Boolean = false

  /** `+TRANSLATOR SELECT` Latch (`TSLTR SEL`, AA172). */
  var tsltrSel: Boolean = false

  /** `+BUSY` Latch (`BSY`, AA142 AC4). */
  var busy: Boolean = false

  /** `+OUTPUT INTERFACE FEEDBACK` Latch (`OUT INTF FDBK`, AA181) and `+OIF LATCH` (`OUT INTF FDBK *`, AA172). */
  var outIntfFdbk: Boolean = false
  var outIntfFdbkLatch: Boolean = false

  /** `+FUNCTION` (`FN`, AA181), `+TAB + CRN` (`AA181`), and `+ORDINARY CHARACTER` (`ORD CHAR`, AA171). */
  var fn: Boolean = false
  var tabOrCrn: Boolean = false
  var ordChar: Boolean = true

  /** `+DUMMY` Latch (`AA172`), `+DUMMY FUNCTION` Latch (`DMY FN`, AA172), `+SYNC 1` (`AA171`), and `+SYNC 2` (`AA172`). */
  var dummy: Boolean = false
  var dmyFn: Boolean = false
  var sync1: Boolean = false
  var sync2: Boolean = false

  /** Translator Cycle Clutch & Composer Print Feedback mechanical contacts / single-shots (`AA171`, `AA172`, `AA181`). */
  var cycClNo: Boolean = false
  var cycClNc: Boolean = true
  var crnInlk: Boolean = false
  var tabOrCrnSs: Boolean = false
  var prFdbk: Boolean = false
  var prFdbkDlyd: Boolean = false
  var sync1Ss175ms: Boolean = true
  var selMCont: Boolean = true

  /** `+TRANSLATOR PRINT` (`TSLTR PR`, AA181 BQ4), `+TRANSLATOR CYCLE CLUTCH` (`TSLTR CYC CL`, AA181), and `+TRANSLATOR SELECT MAGNETS 1..8` (`AA181`). */
  var tsltrPr: Boolean = false
  var tsltrCycCl: Boolean = false
  var tsltrSelMag1: Boolean = false
  var tsltrSelMag2: Boolean = false
  var tsltrSelMag3: Boolean = false
  var tsltrSelMag4: Boolean = false
  var tsltrSelMag5: Boolean = false
  var tsltrSelMag6: Boolean = false
  var tsltrSelMag7: Boolean = false
  var tsltrSelMag8: Boolean = false

  // ===============================================================================================
  // ILD 0312: Edit Selector, CE Aid Controls & Lights (BC011, AA021..AA024, AA052, AA072, AA141, AA151, AA202, AA221, BC021)
  // ===============================================================================================

  /** CE Console Display pushbuttons `DISP 1..4` (`Y, O, N, A` on BC011 / 0312). */
  var disp1Pressed: Boolean = false
  var disp2Pressed: Boolean = false
  var disp3Pressed: Boolean = false
  var disp4Pressed: Boolean = false

  /** `+EDIT SWITCH` contact converter output (`BC011` / `AA221 AM2`): True in `Edit`, `Addr`, `Data`, `Prog`; false in `Proc`. */
  var editSwitchContact: Boolean = false

  /** CE Aid encoding lines `AID 1..3` (`BC011` / `AA221` / `AA052`). */
  var aid1: Boolean = false
  var aid2: Boolean = false
  var aid3: Boolean = false

  /** CE Console Display selector lines `+DISPLAY 3` (`AA052 AD4`), `+DISPLAY 4` (`AA052 AB4`), and `-AID` (`notAid`, `AA052 AS4`). */
  var dply3: Boolean = false
  var dply4: Boolean = false
  var notAid: Boolean = true

  /** `+LIGHTS` Latch (`LITES`, AA072 3B-AD) and `+OUTPUT REGISTER` Display Gate (`OUT REG DPLY`, AA072 AN4). */
  var lites: Boolean = false
  var outRegDply: Boolean = false

  /** `+ERROR LIGHT` (`ERR LT`, AA202 BA4): Magnetic Tape EOT / Unload / Parity Error indicator. */
  var errorLite: Boolean = false

  /** Customer Engineer 4-bit Test Inputs (`+CE TEST INPUT 1..4`, AA022 / AA024). */
  var ceTestInput1: Boolean = false
  var ceTestInput2: Boolean = false
  var ceTestInput3: Boolean = false
  var ceTestInput4: Boolean = false

  /**
   * Hardware Console Indicator Lamps `LITE 1..11` (`-LIGHT 1..11` in positive logic, AA021..AA024 / BC021).
   * Note: Hardware numbers `LITE 1..11` right-to-left (`lite1` = rightmost lamp `Y1`/`O1`, `lite11` = leftmost lamp `Y11`/`Reset`).
   */
  var lite1: Boolean = false
  var lite2: Boolean = false
  var lite3: Boolean = false
  var lite4: Boolean = false
  var lite5: Boolean = false
  var lite6: Boolean = false
  var lite7: Boolean = false
  var lite8: Boolean = false
  var lite9: Boolean = false
  var lite10: Boolean = false
  var lite11: Boolean = false

  // ===============================================================================================
  // Convenience Inspection & Setup Helpers
  // ===============================================================================================

  /** Sets `OUTPUT 1..8` (`out1..out8`) from an 8-bit integer value `0..255`. */
  def setOutputByte(value: Int): Unit = {
    out1 = (value & 0x01) != 0
    out2 = (value & 0x02) != 0
    out3 = (value & 0x04) != 0
    out4 = (value & 0x08) != 0
    out5 = (value & 0x10) != 0
    out6 = (value & 0x20) != 0
    out7 = (value & 0x40) != 0
    out8 = (value & 0x80) != 0
    halt = out1 && out2 && out3 && out4 && out5 && out6 && out7 && out8
  }

  /** Sets `CE TEST INPUT 1..4` from a 4-bit integer mask (`bit 0 = Test 1 .. bit 3 = Test 4`). */
  def setCeTestInputs(mask: Int): Unit = {
    ceTestInput1 = (mask & 0x1) != 0
    ceTestInput2 = (mask & 0x2) != 0
    ceTestInput3 = (mask & 0x4) != 0
    ceTestInput4 = (mask & 0x8) != 0
  }

  /** Sets `KB CODE A..D` and `KB CODE RDY` from a 4-bit keyboard code (`bit 3=A, bit 2=B, bit 1=C, bit 0=D`). */
  def setKeyboardCode(kbCodeABCD: Int, ready: Boolean = true, singleEntry: Boolean = true): Unit = {
    kbCodeA = ((kbCodeABCD >> 3) & 1) != 0
    kbCodeB = ((kbCodeABCD >> 2) & 1) != 0
    kbCodeC = ((kbCodeABCD >> 1) & 1) != 0
    kbCodeD = (kbCodeABCD & 1) != 0
    kbCodeRdy = ready
    kbSingleEntry = singleEntry
  }

  /** Clears `KB CODE A..D`, `KB CODE RDY`, and `KB SINGLE ENTRY`. */
  def clearKeyboardCode(): Unit = {
    kbCodeA = false
    kbCodeB = false
    kbCodeC = false
    kbCodeD = false
    kbCodeRdy = false
    kbSingleEntry = false
  }

  /** Returns hardware `LITE i` (`1..11`, right-to-left: `1` = `lite1`, `11` = `lite11`). */
  def hardwareLite(index: Int): Boolean = index match {
    case 1  => lite1
    case 2  => lite2
    case 3  => lite3
    case 4  => lite4
    case 5  => lite5
    case 6  => lite6
    case 7  => lite7
    case 8  => lite8
    case 9  => lite9
    case 10 => lite10
    case 11 => lite11
    case _  => false
  }

  /** Returns the 11 console lamps ordered left-to-right (`lite11` .. `lite1`), matching `ConsoleLampSpec.AllLamps`. */
  def lampsLeftToRight: Vector[Boolean] =
    Vector(lite11, lite10, lite9, lite8, lite7, lite6, lite5, lite4, lite3, lite2, lite1)

  /** Returns the 7 Translator Select Magnet driver states (`TSLTR SEL MAG 1..7`) as a 7-bit integer (`bit 0 = Mag 1 .. bit 6 = Mag 7`). */
  def translatorSelectMagnetsMask: Int =
    (if (tsltrSelMag1) 0x01 else 0) |
      (if (tsltrSelMag2) 0x02 else 0) |
      (if (tsltrSelMag3) 0x04 else 0) |
      (if (tsltrSelMag4) 0x08 else 0) |
      (if (tsltrSelMag5) 0x10 else 0) |
      (if (tsltrSelMag6) 0x20 else 0) |
      (if (tsltrSelMag7) 0x40 else 0)

  /** Returns the current 11-bit Memory Address Latch value `(Y11..Y1)` in `0..2047`. */
  def malValue: Int =
    (if (y1) 1 else 0) |
      (if (y2) 2 else 0) |
      (if (y3) 4 else 0) |
      (if (y4) 8 else 0) |
      (if (y5) 16 else 0) |
      (if (y6) 32 else 0) |
      (if (y7) 64 else 0) |
      (if (y8) 128 else 0) |
      (if (y9) 256 else 0) |
      (if (y10) 512 else 0) |
      (if (y11) 1024 else 0)

  /** Sets `Y1..Y11` from an integer byte address `0..2047`. */
  def setMalValue(addr: Int): Unit = {
    y1  = (addr & 1) != 0
    y2  = (addr & 2) != 0
    y3  = (addr & 4) != 0
    y4  = (addr & 8) != 0
    y5  = (addr & 16) != 0
    y6  = (addr & 32) != 0
    y7  = (addr & 64) != 0
    y8  = (addr & 128) != 0
    y9  = (addr & 256) != 0
    y10 = (addr & 512) != 0
    y11 = (addr & 1024) != 0
  }

  /** Returns the 4-bit `Q BUFFER` value `(qBfr4..qBfr1)` in `0..15`. */
  def qBufferNibble: Int =
    (if (qBfr1) 1 else 0) |
      (if (qBfr2) 2 else 0) |
      (if (qBfr3) 4 else 0) |
      (if (qBfr4) 8 else 0)

  /** Returns the 4-bit `P BUFFER` value `(pBfr4..pBfr1)` in `0..15`. */
  def pBufferNibble: Int =
    (if (pBfr1) 1 else 0) |
      (if (pBfr2) 2 else 0) |
      (if (pBfr3) 4 else 0) |
      (if (pBfr4) 8 else 0)

  /** Returns the 4-bit `ACCUMULATOR` value `(acc4..acc1)` in `0..15`. */
  def accNibble: Int =
    (if (acc1) 1 else 0) |
      (if (acc2) 2 else 0) |
      (if (acc3) 4 else 0) |
      (if (acc4) 8 else 0)

  /** Returns the 8-bit `OUTPUT 1..8` register value in `0..255`. */
  def outputByte: Int =
    (if (out1) 1 else 0) |
      (if (out2) 2 else 0) |
      (if (out3) 4 else 0) |
      (if (out4) 8 else 0) |
      (if (out5) 16 else 0) |
      (if (out6) 32 else 0) |
      (if (out7) 64 else 0) |
      (if (out8) 128 else 0)

  /** Returns the 7-bit packed Operational Code Latches matching `CcuState.opCodeLatches`. */
  def packedOpCodeLatches: Int =
    (if (oplCde1) 1 else 0) |
      (if (oplCde2) 2 else 0) |
      (if (oplCde3) 4 else 0) |
      (if (oplCde4) 8 else 0) |
      (if (oplCde5) 16 else 0) |
      (if (oplCde6) 32 else 0) |
      (if (oplCde7) 64 else 0)

  /** Returns the current Instruction Time as an integer `0..9` from `insnTCtrA..D` (BCD 8-4-2-1). */
  def insnTime: Int =
    (if (insnTCtrD) 8 else 0) |
      (if (insnTCtrC) 4 else 0) |
      (if (insnTCtrB) 2 else 0) |
      (if (insnTCtrA) 1 else 0)

  /** Returns the steering Instruction Time as an integer `0..9` from `strInsnTCtrA..D` (BCD 8-4-2-1). */
  def steeringInsnTime: Int =
    (if (strInsnTCtrD) 8 else 0) |
      (if (strInsnTCtrC) 4 else 0) |
      (if (strInsnTCtrB) 2 else 0) |
      (if (strInsnTCtrA) 1 else 0)

  /** Returns the current Bit Time index `0..3` (`0` = `B1-4`, `1` = `B5-8`, `2` = `B9-12`, `3` = `B13-16`). */
  def bitTimeIndex: Int =
    (if (btCtrB) 2 else 0) | (if (btCtrA) 1 else 0)

  /** Returns the 5-bit `MT CTR` register `(A, B, C, D, E)` as an integer in `0..31` (`bit 4 = A .. bit 0 = E`). */
  def mtCounterBits: Int =
    (if (mtCtrA) 16 else 0) |
      (if (mtCtrB) 8 else 0) |
      (if (mtCtrC) 4 else 0) |
      (if (mtCtrD) 2 else 0) |
      (if (mtCtrE) 1 else 0)

  /**
   * Returns the decoded Magnetic Tape Counter state number (`0..10`, `13`, `14`, `15`, or `-1` if unmapped)
   * matching the `MT READER COUNTER AND BIT DECODE` timing diagram (`0403` / `ILD 0301` & `0302`).
   */
  def mtCounterNumber: Int = mtCounterBits match {
    case 0x02 => 0  // 00010 (Count 0)
    case 0x10 => 1  // 10000 (Count 1 -> DL1 / R1)
    case 0x18 => 2  // 11000 (Count 2 -> DL2 / R2)
    case 0x0c => 3  // 01100 (Count 3 -> DL3 / R2A)
    case 0x04 => 4  // 00100 (Count 4 -> DL4 / R5)
    case 0x16 => 5  // 10110 (Count 5 -> DL8 / SCH)
    case 0x1e => 6  // 11110 (Count 6 -> DL5 / T1)
    case 0x0b => 7  // 01011 (Count 7 -> DL6 / T2)
    case 0x03 => 8  // 00011 (Count 8 -> DL9 / CK)
    case 0x11 => 9  // 10001 (Count 9 -> DL7 / SHIFT)
    case 0x19 => 10 // 11001 (10 Count)
    case 0x1f => if (mtEotOrUnld || mtCount13) 13 else 14 // 11111 (13 Count Unload / 14 Count Search)
    case 0x0a => 15 // 01010 (15 Count Load)
    case _    => -1
  }

  /** Returns the 9-bit `DATA LATCH 1..9` state as a 9-bit mask (`bit 0 = DL1 .. bit 8 = DL9`). */
  def mtDataLatchesMask: Int =
    (if (mtData1) 0x001 else 0) |
      (if (mtData2) 0x002 else 0) |
      (if (mtData3) 0x004 else 0) |
      (if (mtData4) 0x008 else 0) |
      (if (mtData5) 0x010 else 0) |
      (if (mtData6) 0x020 else 0) |
      (if (mtData7) 0x040 else 0) |
      (if (mtData8) 0x080 else 0) |
      (if (mtData9) 0x100 else 0)

  /** Returns the 8-bit character decoded by `MT BITS AA211` from `DATA LATCH 1..8`. */
  def mtDecodedByte: Int =
    (if (mtData4) 0x01 else 0) | // MT BIT 1 (R5)
      (if (mtData2) 0x02 else 0) | // MT BIT 2 (R2)
      (if (mtData5) 0x04 else 0) | // MT BIT 3 (T1)
      (if (mtData6) 0x08 else 0) | // MT BIT 4 (T2)
      (if (mtData1) 0x10 else 0) | // MT BIT 5 (R1)
      (if (mtData3) 0x20 else 0) | // MT BIT 6 (R2A)
      (if (mtData7) 0x40 else 0) | // MT BIT 7 (SHIFT)
      (if (mtData8) 0x80 else 0)   // MT BIT 8 (SCH)

  /**
   * Loads the 7 Operational Code latches (`OPL CDE 1..7`) from a 16-bit instruction word
   * exactly as `AA032` latches them during `I-1` (`B13-16`, `B9-12`, `B5-8`, `B1-4`).
   */
  def loadOpCodeFromWord(word: Word): Unit = {
    val raw = word.unsigned
    oplCde1 = ((raw >> 15) & 1) == 1
    oplCde2 = ((raw >> 14) & 1) == 1
    oplCde3 = ((raw >> 13) & 1) == 1
    oplCde4 = ((raw >> 12) & 1) == 1
    oplCde5 = ((raw >> 11) & 1) == 1
    oplCde6 = ((raw >> 6) & 1) == 1
    oplCde7 = (raw & 1) == 1
    Ild0304Permute.evaluateCombinational(this)
  }
}
