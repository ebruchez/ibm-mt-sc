package mtsc.cpu.ild

/**
 * Gate- and latch-level implementation of **ILD Page `0311`** (*Translator Output Controls &
 * Output Latches*), corresponding to ALD sheets `[AA031]`, `[AA072]`, `[AA141]`, `[AA142]`,
 * `[AA171]`, `[AA172]`, `[AA181]`, and `[AA233]`.
 */
object Ild0311OutputIo {

  /**
   * Evaluates all combinational signals on **ILD `0311`**:
   * `LD OUT LTH`, `RES OUT LTH`, `HALT`, `UPSET PRE INH`, `CHAR RDY`, `SEL OUT DEV`,
   * `DESEL OUT DEV`, `FN`, `TAB + CRN`, `ORD CHAR`, `TSLTR CYC CL`, `TSLTR PR`,
   * and `TSLTR SEL MAG 1..8`.
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    // ---------------------------------------------------------------------------------------------
    // [AA141 / AA142 / AA031] Output Register Load, Reset & 0xFF Halt Detection
    // ---------------------------------------------------------------------------------------------
    // [AA141 2J-AP, 2K-AU -> 4J-AQ] +LOAD OUTPUT LATCH:
    // Active at I-5, CEN BT of an OUTPUT instruction when READY, or during I-1 (!btCtrB, CEN BT) / KB STRB in Edit mode.
    s.ldOutLth =
      (s.output && s.rdy && s.insnT5 && s.cenBt) ||
      (s.edit && ((s.insnT1 && !s.btCtrB && s.cenBt) || s.kbStrb))

    // [AA142 -> AA031] -RESET OUTPUT LATCH (positive logic `resOutLth`):
    // Clears OUT 1..8 during B1-4 & B when LD OUT LTH is active (before OUT 1..4 are set by P BFR 1..4).
    s.resOutLth = (s.ldOutLth && s.b && s.b1_4) || s.masterReset

    // [AA031 7G-AT] -HALT (positive logic `halt`): all 8 output latches set (`0xFF` end-of-multiplex code)
    s.halt = s.out1 && s.out2 && s.out3 && s.out4 && s.out5 && s.out6 && s.out7 && s.out8

    // ---------------------------------------------------------------------------------------------
    // [AA141 / AA233] Upset Pre-Inhibit & Input Character Readiness
    // ---------------------------------------------------------------------------------------------
    // [AA141] +UPSET PREINHIBIT = -B & +TWR & +B13-16 & +INSN T 1
    s.upsetPreInh = !s.b && s.twr && s.b13_16 && s.insnT1

    // [AA232 / AA233 / AA212] +CHARACTER READY (Magnetic Tape or Paper Tape character ready)
    val mtCharRdy = s.mtTenCount && s.mtCde && !s.mtSch && !s.mtRd && !s.trSchSta && !s.errorLite
    s.charRdy = mtCharRdy || s.ptCharRdy

    // ---------------------------------------------------------------------------------------------
    // [AA142 / AA072] Output Device Selection & Deselection
    // ---------------------------------------------------------------------------------------------
    // [AA142 3F-AM -> 4F-AN] +SELECT OUTPUT DEVICE = CEN BT & INSN T 2 & OUTPUT & RDY & -MPX
    s.selOutDev = s.cenBt && s.insnT2 && s.output && s.rdy && !s.mpx

    // [AA072 1M-AS] +DESELECT OUTPUT DEVICE = SEL OUT DEV & B
    s.deselOutDev = s.selOutDev && s.b

    // ---------------------------------------------------------------------------------------------
    // [AA171 / AA181] Translator Function, Tab/CR, Ordinary Character & Magnet Drivers
    // ---------------------------------------------------------------------------------------------
    // [AA181 3B-AD, 3C-AE] +FUNCTION (FN):
    // True when OUT 3 (function bit) is set with -DMY FN & -OUT INTF FDBK, or during DMY FN & BSY.
    s.fn = (s.out3 && !s.dmyFn && !s.outIntfFdbk) || (s.dmyFn && s.busy)

    // [AA181 1J-BC, 1N-BL] +TAB + CARRIAGE RETURN (TAB + CRN)
    s.tabOrCrn =
      s.tsltrSel &&
      !s.out1 &&
      !s.out3 &&
      ((!s.out2 && s.out6) || (s.out2 && s.out5))

    // [AA171 / AA181] +ORDINARY CHARACTER (ORD CHAR)
    s.ordChar = !s.fn && !s.tabOrCrn

    // [AA181] +TRANSLATOR CYCLE CLUTCH (TSLTR CYC CL) and +TRANSLATOR PRINT (TSLTR PR)
    val charOrDmyOrCl = (s.tsltrSel && !s.out2 && s.out6) || s.dummy || (!s.dmyFn && s.cycClNc)
    val fnStart       = s.fn && s.busy && s.tsltrSel && !s.outIntfFdbk
    val charStart     = s.busy && s.tsltrSel && !s.outIntfFdbk && charOrDmyOrCl

    s.tsltrCycCl = !s.masterReset && !s.sync1 && (fnStart || charStart)
    s.tsltrPr    = s.tsltrCycCl && !s.dummy

    // [AA181] +TRANSLATOR SELECT MAGNETS 1..8
    s.tsltrSelMag1 = s.tsltrPr && s.out1
    s.tsltrSelMag2 = s.tsltrPr && s.out2
    s.tsltrSelMag3 = s.tsltrPr && s.out3
    s.tsltrSelMag4 = s.tsltrPr && s.out4
    s.tsltrSelMag5 = s.tsltrPr && s.out5
    s.tsltrSelMag6 = s.tsltrPr && s.out6
    s.tsltrSelMag7 = s.tsltrPr && s.out7
    s.tsltrSelMag8 = s.tsltrPr && s.selMCont && s.out8
  }

  /**
   * Updates the 8 Output Register Latches (`OUT 1..8` on `[AA031]`) from `P BUFFER 1..4`
   * when `LD OUT LTH` and/or `RES OUT LTH` are active.
   */
  def updateOutputLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    if (s.resOutLth || s.ldOutLth) {
      // [AA031] -RESET OUTPUT LATCH breaks the hold path on OUT 1..8 during B1-4 & B & LD OUT LTH (or Master Reset)
      if (s.resOutLth) {
        s.out1 = false
        s.out2 = false
        s.out3 = false
        s.out4 = false
        s.out5 = false
        s.out6 = false
        s.out7 = false
        s.out8 = false
      }

      // [AA031] +LOAD OUTPUT LATCH sets OUT 1..4 at B1-4 and OUT 5..8 at B5-8 from P BFR 1..4
      if (s.ldOutLth) {
        if (s.b1_4) {
          if (s.pBfr1) s.out1 = true
          if (s.pBfr2) s.out2 = true
          if (s.pBfr3) s.out3 = true
          if (s.pBfr4) s.out4 = true
        } else if (s.b5_8) {
          if (s.pBfr1) s.out5 = true
          if (s.pBfr2) s.out6 = true
          if (s.pBfr3) s.out7 = true
          if (s.pBfr4) s.out8 = true
        }
      }

      evaluateCombinational(s)
    }
  }

  /**
   * Updates all I/O handshaking and Translator synchronizer latches on **ILD `0311`**
   * (`MPX`, `PRE INH`, `INH`, `TSLTR SEL`, `BSY`, `READY`, `OUT INTF FDBK`, `OUT INTF FDBK *`,
   * `DUMMY`, `SYNC 2`, `DMY FN`, and `SYNC 1`).
   */
  def updateControlLatches(s: BackplaneSignals): Unit = {
    evaluateCombinational(s)

    // ---------------------------------------------------------------------------------------------
    // 1. [AA141 2B-AA..2E-AM] +MULTIPLEX Latch (MPX)
    // ---------------------------------------------------------------------------------------------
    if (s.masterReset || ((s.edit || s.masterReset || s.halt) && s.begPrgStp)) {
      s.mpx = false
    }
    if (s.output && s.rdy && s.y11 && s.insnT2 && s.b13_16 && s.cenBt) {
      s.mpx = true
    }

    // ---------------------------------------------------------------------------------------------
    // 2. [AA142 2H-AP..5H-AT] +PREINHIBIT Latch (PRE INH)
    // ---------------------------------------------------------------------------------------------
    val startKeyAdvanceInEdit =
      s.edit && s.kbCodeA && s.kbCodeB && s.kbCodeD && !s.kbSingleEntry
    val resetPreInh =
      s.masterReset ||
      (s.upsetPreInh && s.strLthStrobe) ||
      startKeyAdvanceInEdit

    if (resetPreInh) {
      s.preInh = false
    }

    val setPreInhI1 =
      ((s.output && (s.mpx || !s.rdy)) || (s.input && s.kbSingleEntry)) && s.upsetPreInh
    val setPreInhEdit =
      s.edit && s.cenBt && !startKeyAdvanceInEdit
    val setPreInhTape =
      s.input && !s.kbCde && !s.charRdy && s.insnT2 && s.b13_16 && !s.b && s.twr

    if (setPreInhI1 || setPreInhEdit || setPreInhTape) {
      s.preInh = true
    }

    // ---------------------------------------------------------------------------------------------
    // 3. [AA142] +INHIBIT Latch (INH)
    // ---------------------------------------------------------------------------------------------
    if (s.masterReset || (!s.preInh && s.twr)) {
      s.inh = false
    }
    if (!s.btCtrB && s.preInh) {
      s.inh = true
    }

    // ---------------------------------------------------------------------------------------------
    // 4. [AA172] +TRANSLATOR SELECT Latch (TSLTR SEL)
    // ---------------------------------------------------------------------------------------------
    val setTsltrSel = (!s.y8 && !s.y9 && !s.y10 && s.selOutDev) || s.mpx
    if (s.masterReset) {
      s.tsltrSel = false
    } else if (setTsltrSel) {
      s.tsltrSel = true
    } else if (s.deselOutDev) {
      s.tsltrSel = false
    }

    // ---------------------------------------------------------------------------------------------
    // 5. [AA142 2B-AA..2D-AJ] +BUSY Latch (BSY)
    // ---------------------------------------------------------------------------------------------
    if (s.masterReset || ((s.edit || s.masterReset || s.outIntfFdbkLatch) && s.cenBt)) {
      s.busy = false
    }
    if ((!s.mpx || !s.halt) && s.output && s.rdy && !s.lites && s.insnT5 && s.b13_16 && s.strLthStrobe) {
      s.busy = true
    }

    // ---------------------------------------------------------------------------------------------
    // 6. [AA142 5M-BE..5N-BG] +READY Latch (RDY)
    // ---------------------------------------------------------------------------------------------
    if (s.busy && s.twr) {
      s.rdy = false
    }
    if (s.masterReset || ((!s.outIntfFdbkLatch && s.begPrgStp && !s.busy) || (s.edit && !s.busy))) {
      s.rdy = true
    }

    // ---------------------------------------------------------------------------------------------
    // 7. [AA181 / AA172] +OUTPUT INTERFACE FEEDBACK (`outIntfFdbk`) & `+OIF LATCH` (`outIntfFdbkLatch`)
    // ---------------------------------------------------------------------------------------------
    val resetOutIntfFdbk =
      s.masterReset ||
      (!s.dummy && !s.tabOrCrn && s.cycClNc && !s.crnInlk && !s.tabOrCrnSs) ||
      (s.dmyFn && s.cycClNc)
    if (resetOutIntfFdbk) {
      s.outIntfFdbk = false
    }
    if (s.cycClNo) {
      s.outIntfFdbk = true
    }

    if (s.masterReset || ((s.fn || s.out3 || s.sync2 || s.mpx) && !s.outIntfFdbk)) {
      s.outIntfFdbkLatch = false
    }
    if (s.outIntfFdbk && s.insnT5) {
      s.outIntfFdbkLatch = true
    }

    // ---------------------------------------------------------------------------------------------
    // 8. [AA171 / AA172] Translator Synchronizer Latches (`DUMMY`, `SYNC 2`, `DMY FN`, `SYNC 1`)
    // ---------------------------------------------------------------------------------------------
    if (s.masterReset || !s.outIntfFdbk) {
      s.dummy = false
      s.sync2 = false
    }
    if (s.fn && s.cycClNo) {
      s.dummy = true
    }
    if (s.outIntfFdbk && s.cycClNc) {
      s.sync2 = true
    }

    if (s.masterReset || (s.prFdbkDlyd && !s.prFdbk)) {
      s.dmyFn = false
    }
    if ((s.dummy && s.sync2 && s.cycClNo) || (s.rdy && s.upsetPreInh && s.prFdbk)) {
      s.dmyFn = true
    }

    if (s.masterReset || s.prFdbkDlyd || !s.sync1Ss175ms) {
      s.sync1 = false
    }
    val setSync1Char = s.upsetPreInh && s.rdy && s.prFdbk && !s.prFdbkDlyd && s.ordChar
    val setSync1Fn   = !s.fn && s.dmyFn && !s.outIntfFdbk
    if (setSync1Char || setSync1Fn) {
      s.sync1 = true
    }

    evaluateCombinational(s)
  }
}
