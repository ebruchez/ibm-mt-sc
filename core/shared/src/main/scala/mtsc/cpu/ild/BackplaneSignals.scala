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
  // Convenience Inspection & Setup Helpers
  // ===============================================================================================

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
  }
}
