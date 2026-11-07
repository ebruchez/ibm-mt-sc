package mtsc.cpu.ild

import mtsc.console.{CeEditSelector, CeStepGranularity}

/**
 * Four sub-phases of one 8.33 us `(SSA, SSB)` oscillator cycle, corresponding directly to the
 * vertical grid columns of **Timing Diagram `0601`** (*Basic Clock and Timing Logic*):
 * - [[OscSubPhase.SsaOn]]: `SSA` 2.4 us single-shot active (`ssa = true, ssb = false`)
 * - [[OscSubPhase.SsaOff]]: 1.77 us inter-pulse gap after `SSA` (`ssa = false, ssb = false`)
 * - [[OscSubPhase.SsbOn]]: `SSB` 2.4 us single-shot active (`ssa = false, ssb = true`)
 * - [[OscSubPhase.SsbOff]]: 1.77 us inter-pulse gap after `SSB` (`ssa = false, ssb = false`)
 */
enum OscSubPhase {
  case SsaOn, SsaOff, SsbOn, SsbOff
}

/**
 * Gate- and latch-level implementation of **ILD Page `0303`** (*Oscillator, Clock, Bit Time Counter,
 * Start & Stop Logic*), corresponding to ALD sheets `[AA071]`, `[AA081]`, `[AA082]`, `[AA083]`,
 * `[AA092]`, `[AA131]`, and `[AA221]`.
 */
object Ild0303Clock {

  /**
   * Applies `+MASTER RESET` (`[AA151]`) and places the clock immediately prior to `SSA #1`
   * of `I-0, BIT 1-4` (column 0 of **Timing Diagram `0601`**).
   */
  def resetBeforeFirstSsa(s: BackplaneSignals): Unit = {
    s.masterReset = true
    s.oscTrigger = false
    s.ssa = false
    s.ssb = false
    s.stpClk1 = false
    s.stpClk2 = false

    // Quadrature & trinary clock state immediately prior to SSA #1 of BIT 1-4 (Timing Diagram 0601)
    s.a = true
    s.b = true
    s.twr = false
    s.pTime = true
    s.cenBt = false

    // Bit Time steering & indicator latches reset to 00 (BIT 1-4)
    s.strBtCtrA = false
    s.strBtCtrB = false
    s.btCtrA = false
    s.btCtrB = false

    // Instruction Time steering & indicator latches reset to 0000 (INSN T 0)
    Ild0304Permute.updateSteeringLatches(s)
    Ild0304Permute.updateIndicatorLatches(s)

    s.masterReset = false
    evaluateCombinational(s)
  }

  /**
   * Applies `+MASTER RESET` (`[AA151]`) and completes the initial `SSA #1` indicator load of
   * `I-0, BIT 1-4` so that each subsequent call to [[stepBitTime]] advances exactly 1 Bit Time
   * (8 single-shots for `P-Only`, 12 single-shots for `P & Q`) and each call to [[stepWordTime]]
   * advances 1 full 16-bit Instruction Time (4 Bit Times).
   */
  def reset(s: BackplaneSignals): Unit = {
    resetBeforeFirstSsa(s)
    pulseSsaOn(s)
    if (!s.stpClk1) {
      pulseSsaOff(s)
    }
  }

  /**
   * Evaluates the CE Console Clock Control Switch wafers and Edit Switch (`[AA071]`, `[AA141]`, `[AA221]`).
   */
  def evaluateSwitchWafers(s: BackplaneSignals): Unit = {
    val newEdit = s.editSwitch != CeEditSelector.Proc
    val editChanged = s.edit != newEdit
    s.edit = newEdit
    s.stepButtonReleased = !s.stepButtonPressed

    if (s.clockSwitch == CeStepGranularity.Auto) {
      s.autoOrStep = true
      s.step1 = false
      s.notStep1 = false
      s.step2 = false
      s.notStep2 = false
    } else {
      s.autoOrStep = s.stepButtonPressed
      // 2-wafer Clock Control Switch encoding on ILD 0303 / AA221:
      // - Top wafer (STEP 1): grounded at JMP and WORD -> +STEP 1 is true at PRG STP and BIT
      // - Middle wafer (STEP 2): grounded at JMP and PRG STP -> +STEP 2 is true at WORD and BIT
      s.clockSwitch match {
        case CeStepGranularity.Auto => ()
        case CeStepGranularity.Jump =>
          s.step1 = false
          s.notStep1 = true
          s.step2 = false
          s.notStep2 = true
        case CeStepGranularity.ProgramStep =>
          s.step1 = true
          s.notStep1 = false
          s.step2 = false
          s.notStep2 = true
        case CeStepGranularity.Word =>
          s.step1 = false
          s.notStep1 = true
          s.step2 = true
          s.notStep2 = false
        case CeStepGranularity.Bit =>
          s.step1 = true
          s.notStep1 = false
          s.step2 = true
          s.notStep2 = false
      }
    }

    if (editChanged) {
      Ild0304Permute.evaluateCombinational(s)
    }
  }

  /**
   * Evaluates `STOP CLOCK 1` and `STOP CLOCK 2` latches on `[AA071]`.
   */
  def evaluateStopClockLatches(s: BackplaneSignals): Unit = {
    if (s.clockSwitch == CeStepGranularity.Auto && !s.stpClk1) {
      val newEdit = s.editSwitch != CeEditSelector.Proc
      if (s.edit != newEdit) {
        s.edit = newEdit
        Ild0304Permute.evaluateCombinational(s)
      }
      s.stepButtonReleased = !s.stepButtonPressed
      s.autoOrStep = true
      s.step1 = false
      s.notStep1 = false
      s.step2 = false
      s.notStep2 = false
      if (s.ssb && s.a) {
        s.stpClk2 = true
      }
      return
    }

    evaluateSwitchWafers(s)

    // [AA071 2K-AM / 2L-AN] STOP CLOCK 2 latch:
    // - Reset (2K-AM): +STOP CLOCK 1 & +STEP BUTTON (button released while clock stopped)
    // - Set (2L-AN): +SINGLE SHOT B & +A & -STOP CLOCK 1
    if (s.stpClk1 && s.stepButtonReleased) {
      s.stpClk2 = false
    } else if (s.ssb && s.a && !s.stpClk1) {
      s.stpClk2 = true
    }

    // [AA071 1B-AA] Stop gate enable: active when NOT (Auto or Step pressed), OR after STP CLK 2 has set
    val stopGateEnable = !s.autoOrStep || s.stpClk2

    // [AA071 2C-AC .. 2G-AF] 4 Stop Mode AND gates:
    // - 2G-AF + 2E-AE (JMP): -STEP 1 & -STEP 2 & +JUMP & +TAKEN & +CEN BT & -P TIME & +B13-16 & +INSN T 1
    val stopJmp = s.notStep1 && s.notStep2 && s.jmp && s.tkn && s.cenBt && !s.pTime && s.b13_16 && s.insnT1
    // - 2E-AE (PRG STP): +STEP 1 & -STEP 2 & +CEN BT & -P TIME & +B13-16 & +INSN T 1
    val stopPrgStp = s.step1 && s.notStep2 && s.cenBt && !s.pTime && s.b13_16 && s.insnT1
    // - 2D-AD (WORD): -STEP 1 & +STEP 2 & -STR BT CTR A & -STR BT CTR B & +RES INDS
    val stopWord = s.notStep1 && s.step2 && !s.strBtCtrA && !s.strBtCtrB && s.resInds
    // - 2C-AC (BIT): +STEP 1 & +STEP 2 & +RES INDS
    val stopBit = s.step1 && s.step2 && s.resInds

    val stopCondition = stopJmp || stopPrgStp || stopWord || stopBit

    // [AA071 4C-AH / 3D-AG / 5D-AK] STOP CLOCK 1 latch:
    // - Reset (4C-AH): +AUTO OR STEP & -STOP CLOCK 2
    // - Set (3D-AG): stopGateEnable & stopCondition
    if (s.autoOrStep && !s.stpClk2) {
      s.stpClk1 = false
    } else if (stopGateEnable && stopCondition) {
      s.stpClk1 = true
    }

    // Re-evaluate STP CLK 2 reset if STP CLK 1 just set while button is released
    if (s.stpClk1 && s.stepButtonReleased) {
      s.stpClk2 = false
    }
  }

  /**
   * Fast internal evaluation of `0303` clock, bit-counter decode, current-source timing signals,
   * and `0304` clock strobes (`prmtInsnTStrLth`, `resInsnTStrLth`, `begPrgStp`), without
   * re-running all `0304` instruction-time and opcode decodes when `insnTCtrA..D` and `oplCde1..7`
   * have not changed.
   */
  private[ild] def evaluateClockNets(s: BackplaneSignals): Unit = {
    // [AA081 / AA083] Bit Time Counter Decodes
    val btA = s.btCtrA
    val btB = s.btCtrB
    s.b1_4   = !btA && !btB // [AA081 1G-AZ / 2G-BA]
    s.b5_8   =  btA && !btB // [AA081 1N-BU / 2N-BV]
    s.b9_12  = !btA &&  btB // [AA083]
    s.b13_16 =  btA &&  btB // [AA083]

    val a = s.a
    val b = s.b
    val twr = s.twr
    val pTime = s.pTime
    val cenBt = s.cenBt

    // [AA081 5B-AH] -P TIME* = !(pTime && !cenBt)
    val notPTimeStar = !(pTime && !cenBt)
    s.notPTimeStar = notPTimeStar

    // [AA083 AN4] -B AND -TIME WRITE
    s.notBAndNotTwr = !b && !twr

    // [AA081 7H-BD] +BUMP STROBE TIME = -A & +B & +CEN BT
    s.bmpStrbT = !a && b && cenBt

    // [AA081 6F-AX] +STEER LATCH STROBE = -A & -B & +TWR
    s.strLthStrobe = !a && !b && twr

    // [AA083 AQ4] +PERMUTE TIME = +BIT 13-16 & -B & +TWR (-PERMUTE TIME is !prmtTime)
    s.prmtTime = s.b13_16 && !b && twr

    // [AA082 2N-BH / AA092 9L-BE] +RESET INDICATORS = +SSA & +B & +P TIME & -CEN BT
    s.resInds = s.ssa && b && pTime && !cenBt

    // [AA083 AE4] +LOAD INDICATORS = -A & +B & +P TIME
    s.ldInds = !a && b && pTime

    // [AA082 1K-AY, 1L-BB -> 3L-BC] +CURRENT SOURCE X LOGIC
    val memEnable = !s.inhibitMemory && s.selectMemory
    val readX  =  a && !b && !twr && notPTimeStar
    val writeX = !a &&  b && (cenBt || twr) && notPTimeStar
    s.csXLog = memEnable && (readX || writeX)

    // [AA082 1B-AA..1F-AP -> 3D-BJ] +CURRENT SOURCE Y LOGIC
    val readY  =  a &&  b && notPTimeStar
    val writeY = !a && !b && (cenBt || twr)
    s.csYLog = memEnable && (readY || writeY)

    // [Timing Diagram 0601 / AA023] REWO & ROWE
    val abSame = (a && b) || (!a && !b)
    s.rewo = cenBt && abSame
    s.rowe = !cenBt && !pTime && ((a && b) || (!a && !b && twr))

    Ild0304Permute.evaluateClockStrobes(s)
    evaluateStopClockLatches(s)
  }

  /**
   * Evaluates all combinational clock, bit-counter decode, and current-source timing signals
   * on `[AA081]`, `[AA082]`, `[AA083]`, and `[AA092]`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    evaluateSwitchWafers(s)
    Ild0304Permute.evaluateCombinational(s)
    evaluateClockNets(s)
  }

  /**
   * Updates the Bit Time Counter Steering Latches (`STR BIT TIME CTR A, B` on `[AA082]`)
   * during `STR LTH STROBE` / `PERMUTE TIME`.
   */
  def updateBitSteeringLatches(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.strBtCtrA = false
      s.strBtCtrB = false
      return
    }

    // [AA082 4E-AK, 4F-AQ -> 7E-AN] STR BIT TIME CTR A toggles on STR LTH STROBE
    if (s.strLthStrobe) {
      val curBtA = s.btCtrA
      val curBtB = s.btCtrB
      s.strBtCtrA = !curBtA

      // [AA082 4L-BD -> 5L-BE] STR BIT TIME CTR B sets on B5-8 & STR LTH STROBE
      if (curBtA && !curBtB) {
        s.strBtCtrB = true
      }
    }

    // [AA082 4M-BG] STR BIT TIME CTR B resets on PERMUTE TIME (B13-16 & -B & TWR)
    if (s.prmtTime) {
      s.strBtCtrB = false
    }
  }

  /**
   * Updates the Bit Time Counter Indicator Latches (`BIT TIME CTR A, B` on `[AA082]`)
   * during `RES INDS` and `LD INDS`.
   */
  def updateBitIndicatorLatches(s: BackplaneSignals): Unit = {
    if (s.masterReset) {
      s.btCtrA = false
      s.btCtrB = false
      return
    }

    val prevBtA = s.btCtrA

    // [AA082 4C-AF, 4J-AX] Reset by RES INDS (BT CTR B reset is gated by BT CTR A on 4J-AX)
    if (s.resInds) {
      s.btCtrA = false
      if (prevBtA) {
        s.btCtrB = false
      }
    }

    // [AA082 4B-AB, 4H-AU] Set by LD INDS & STR BIT TIME CTR A/B (Set overrides Reset)
    if (s.ldInds) {
      if (s.strBtCtrA) s.btCtrA = true
      if (s.strBtCtrB) s.btCtrB = true
    }
  }

  /**
   * Executes the `SSA` 2.4 us pulse-ON sub-phase (`[AA131]`, `[AA081]`, `[AA082]`, `[AA091]`):
   * updates `A`, `TWR`, `P TIME`, `CEN BT`, and (when `RES INDS` / `LD INDS` fire) the
   * Bit Time and Instruction Time indicator latches.
   */
  def pulseSsaOn(s: BackplaneSignals): Unit = {
    s.oscTrigger = true
    s.ssa = true
    s.ssb = false

    val prevB      = s.b
    val prevTwr    = s.twr
    val prevPTime  = s.pTime
    val prevCenBt  = s.cenBt

    // [AA081 1B-AF, 1C-AA -> 4B-AD] A LATCH: sets on SSA & -B, resets on SSA & +B
    s.a = !prevB

    if (s.a && !s.b) {
      // Transition to +A & -B:
      // - [AA081 5C-AL -> 7B-AK] P TIME sets if TWR was on
      if (prevTwr) s.pTime = true
      // - [AA081 4L-BM..6N-BT -> 7L-BQ] CEN BT sets if -TWR & +P TIME; resets if +TWR or -P TIME
      if (!prevTwr && prevPTime) s.cenBt = true
      else if (prevTwr || !prevPTime) s.cenBt = false
      // - [AA081 3J-BG] TWR resets at +A & -B
      s.twr = false
    } else if (!s.a && s.b) {
      // Transition to -A & +B:
      if (prevCenBt) {
        // [AA081 7H-BD] BUMP STROBE TIME (-A & +B & +CEN BT) resets P TIME:
        s.pTime = false
        // [AA081 1L-BE] In P-ONLY cycle, -A & +B & +CEN BT & +P ONLY sets TWR immediately:
        if (s.pOnly) s.twr = true
      } else if (!prevPTime) {
        // [AA081 1K-BJ] In P&Q cycle at end of Q Read, -A & +B & -CEN BT & -P TIME sets TWR:
        s.twr = true
      }
    }

    // Recompute combinational pulses (RES INDS, LD INDS, BMP STRB T, etc.)
    evaluateClockNets(s)

    // If RES INDS or LD INDS is active (-A & +B & +P TIME & -CEN BT), update indicator latches
    if (s.resInds || s.ldInds) {
      updateBitIndicatorLatches(s)
      Ild0304Permute.updateIndicatorLatches(s)
      evaluateClockNets(s)
    }
  }

  /**
   * Executes the 1.77 us inter-pulse gap after `SSA` expires (`ssa = false, ssb = false`).
   * Note that `LD INDS` (`-A & +B & +P TIME`) remains asserted during this sub-phase while
   * `RES INDS` (`SSA & ...`) is now deasserted, matching Timing Diagram `0601`.
   */
  def pulseSsaOff(s: BackplaneSignals): Unit = {
    s.ssa = false
    s.ssb = false
    s.resInds = false
    if (s.ldInds) {
      updateBitIndicatorLatches(s)
      Ild0304Permute.updateIndicatorLatches(s)
      evaluateClockNets(s)
    }
  }

  /**
   * Executes the `SSB` 2.4 us pulse-ON sub-phase (`[AA131]`, `[AA081]`, `[AA082]`, `[AA091]`):
   * updates `B`, `STP CLK 2`, and (when `STR LTH STROBE` / `PERMUTE TIME` fire) the
   * Bit Time and Instruction Time steering latches.
   */
  def pulseSsbOn(s: BackplaneSignals): Unit = {
    s.oscTrigger = false
    s.ssa = false
    s.ssb = true

    // [AA081 1E-AP, 1F-AU -> 4E-AT] B LATCH: sets on SSB & +A, resets on SSB & -A
    s.b = s.a

    // Recompute combinational pulses (STR LTH STROBE, PERMUTE TIME, STP CLK 2, etc.)
    evaluateClockNets(s)

    // If STR LTH STROBE or PERMUTE TIME is active (-A & -B & +TWR), update steering latches
    if (s.strLthStrobe || s.prmtTime) {
      // Important: update 0304 steering latches and 0303 bit steering latches from current indicators
      Ild0304Permute.updateSteeringLatches(s)
      updateBitSteeringLatches(s)
      evaluateClockNets(s)
    }
  }

  /**
   * Executes the 1.77 us inter-pulse gap after `SSB` expires (`ssa = false, ssb = false`).
   */
  def pulseSsbOff(s: BackplaneSignals): Unit = {
    s.ssa = false
    s.ssb = false
    if (s.strLthStrobe || s.prmtTime) {
      Ild0304Permute.updateSteeringLatches(s)
      updateBitSteeringLatches(s)
      evaluateClockNets(s)
    }
  }

  /**
   * Steps a specific oscillator sub-phase (`SsaOn`, `SsaOff`, `SsbOn`, `SsbOff`), corresponding
   * to one vertical column of **Timing Diagram `0601`**.
   *
   * Returns `false` if the clock is stopped by `STP CLK 1` (and cannot be started by `AUTO` or `STEP`).
   */
  def stepSubPhase(s: BackplaneSignals, phase: OscSubPhase): Boolean = {
    evaluateStopClockLatches(s)
    if (s.stpClk1) {
      s.ssa = false
      s.ssb = false
      return false
    }

    phase match {
      case OscSubPhase.SsaOn  => pulseSsaOn(s)
      case OscSubPhase.SsaOff => pulseSsaOff(s)
      case OscSubPhase.SsbOn  => pulseSsbOn(s)
      case OscSubPhase.SsbOff => pulseSsbOff(s)
    }
    true
  }

  /**
   * Advances the oscillator by one 240 kHz single-shot cycle (`SSA` ON + OFF, or `SSB` ON + OFF,
   * 4.167 us). Returns `false` if the oscillator is inhibited by `STP CLK 1`.
   */
  def stepSingleShot(s: BackplaneSignals): Boolean = {
    evaluateStopClockLatches(s)
    if (s.stpClk1) {
      s.ssa = false
      s.ssb = false
      return false
    }

    if (!s.oscTrigger) {
      pulseSsaOn(s)
      if (!s.stpClk1) {
        pulseSsaOff(s)
      }
    } else {
      pulseSsbOn(s)
      if (!s.stpClk1) {
        pulseSsbOff(s)
      }
    }
    true
  }

  /**
   * Advances the clock through one complete Bit Time (`B1-4`, `B5-8`, `B9-12`, or `B13-16`),
   * stopping right after `LD INDS` loads the next Bit Time's indicator latches (or earlier if
   * `STP CLK 1` stops the clock).
   *
   * Returns the number of single-shot pulses (`SSA` + `SSB`) executed (8 for a 33.3 us `P-Only`
   * bit time, 12 for a 50.0 us `P & Q` bit time).
   */
  def stepBitTime(s: BackplaneSignals): Int = {
    var pulses = 0
    var loadedNextBit = false

    while (!loadedNextBit && pulses < 32) {
      evaluateStopClockLatches(s)
      if (s.stpClk1) return pulses

      if (!s.oscTrigger) {
        pulseSsaOn(s)
        pulses += 1
        val hadLoadInd = s.ldInds && !s.cenBt
        if (!s.stpClk1) {
          pulseSsaOff(s)
        }
        // If we already completed at least one full read/write cycle (pulses > 1) and just hit
        // the next bit time's RES INDS / LD INDS, we are at the start of the new Bit Time.
        if (hadLoadInd && pulses > 1) {
          loadedNextBit = true
        }
      } else {
        pulseSsbOn(s)
        pulses += 1
        if (!s.stpClk1) {
          pulseSsbOff(s)
        }
      }
    }
    pulses
  }

  /**
   * Advances the clock through one complete 16-bit Word / Instruction Time (`B1-4` -> `B5-8` ->
   * `B9-12` -> `B13-16` -> next `B1-4`), returning the total number of single-shot pulses executed
   * (32 for a `P-Only` word time = 133.3 us; 48 for a `P & Q` word time = 200.0 us).
   */
  def stepWordTime(s: BackplaneSignals): Int = {
    var totalPulses = 0
    // If we are before SSA #1 of B1-4 (a=true, b=true, pTime=true, cenBt=false),
    // the first SSA #1 enters B1-4, and then 4 full bit times bring us to B1-4 of the next word.
    var bitTransitions = 0
    val targetTransitions = if (s.a && s.b && s.pTime && !s.cenBt) 5 else 4

    while (bitTransitions < targetTransitions && totalPulses < 128) {
      evaluateStopClockLatches(s)
      if (s.stpClk1) return totalPulses

      if (!s.oscTrigger) {
        pulseSsaOn(s)
        totalPulses += 1
        val isBitBoundary = s.ldInds && !s.cenBt
        if (!s.stpClk1) {
          pulseSsaOff(s)
        }
        if (isBitBoundary) {
          bitTransitions += 1
        }
      } else {
        pulseSsbOn(s)
        totalPulses += 1
        if (!s.stpClk1) {
          pulseSsbOff(s)
        }
      }
    }
    totalPulses
  }
}
