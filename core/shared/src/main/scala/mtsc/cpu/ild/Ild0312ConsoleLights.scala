package mtsc.cpu.ild

import mtsc.console.{CeDisplayMode, CeEditSelector}

/**
 * Gate- and latch-level implementation of **ILD Page `0312`** (*Edit Selector, CE Aid Controls &
 * Lights*), corresponding to ALD sheets `[BC011]`, `[AA021]`–`[AA024]`, `[AA052]`, `[AA072]`,
 * `[AA141]`, `[AA151]`, `[AA202]`, `[AA221]`, and `[BC021]`.
 */
object Ild0312ConsoleLights {

  /**
   * Evaluates the 5-position `EDIT MEMORY SELECTOR` switch wafers (`Proc`, `Edit`, `Addr`, `Data`, `Prog`),
   * the 4 CE Display pushbuttons (`DISP 1..4`), the `AID 1..3` lines, and the decoded `DPLY 1..4` and `-AID`
   * signals (`[BC011]`, `[AA221]`, `[AA052]`).
   */
  def evaluateSwitchAndDisplayDecodes(s: BackplaneSignals): Unit = {
    // [BC011 3B-AG -> AA221 AM2] +EDIT SWITCH: closed in Edit, Addr, Data, Prog; open in Proc
    s.editSwitchContact = s.editSwitch != CeEditSelector.Proc

    // [BC011 3D-AH] Display pushbuttons DISP 1..4 have ground return only in Proc and Edit positions
    val inProcOrEdit =
      (s.editSwitch == CeEditSelector.Proc) || (s.editSwitch == CeEditSelector.Edit)

    // [BC011 7C-AU -> AA221 AL2 -> AA052] +AID 1: DISP 3 (N) or DISP 4 (A) in Proc/Edit, or Prog switch position
    s.aid1 =
      (inProcOrEdit && (s.disp3Pressed || s.disp4Pressed)) ||
      (s.editSwitch == CeEditSelector.Prog)

    // [BC011 7E-AV -> AA221 AD2 -> AA052] +AID 2: DISP 1 (Y) or DISP 2 (O) in Proc/Edit, or Addr / Data switch positions
    s.aid2 =
      (inProcOrEdit && (s.disp1Pressed || s.disp2Pressed)) ||
      (s.editSwitch == CeEditSelector.Addr) ||
      (s.editSwitch == CeEditSelector.Data)

    // [BC011 7G-AW -> AA221 AB2 -> AA052] +AID 3: DISP 2 (O) or DISP 4 (A) in Proc/Edit, or Data switch position
    s.aid3 =
      (inProcOrEdit && (s.disp2Pressed || s.disp4Pressed)) ||
      (s.editSwitch == CeEditSelector.Data)

    // [AA052] Decoded Display lines DPLY 1..4 and -AID
    s.dply4  = s.aid1 && s.aid3   // [AA052 1B-AA..2B-AB / AB4] Display 4 (A): Rdy, Mpx, QB 4..1, PB 4..1
    s.dply3  = s.aid1 && !s.aid3  // [AA052 4B-AC..5B-AD / AD4] Display 3 (N): Inh, -Low, Eq, OP 7..1
    s.dply2  = s.aid2 && s.aid3   // [AA052 4E-AL..5E-AM / AM4] Display 2 (O): EOT, Busy, O 8..1
    s.dply1  = s.aid2 && !s.aid3  // [AA052 2D-AH..3D-AJ / AJ4] Display 1 (Y): Y 11..1
    s.notAid = !s.aid1 && !s.aid2 // [AA052 2G-AQ..3G-AS / AS4] -AID: no Display pushbutton or switch override active
  }

  /**
   * Evaluates all combinational signals on **ILD `0312`**:
   * `editSwitchContact`, `aid1..3`, `dply1..4`, `notAid`, `outRegDply`, and all 11 console
   * indicator lamps (`LITE 1..11` on `[AA021]`–`[AA024]`).
   */
  def evaluateCombinational(s: BackplaneSignals): Unit = {
    evaluateSwitchAndDisplayDecodes(s)

    // [AA072 1H-AL, 1J-AP -> 2H-AM -> 3H-AN / AN4] +OUTPUT REGISTER display gate:
    // Gates OUT 1..8 and BUSY onto LITE 1..9 when DPLY 2 is active or in normal Operator LITES mode.
    s.outRegDply = s.dply2 || (s.lites && s.notAid && s.autoOrStep)

    val clockTestDply = s.notAid && !s.autoOrStep

    // ---------------------------------------------------------------------------------------------
    // [AA021..AA024 / BC021] 11 Console Indicator Lamps (LITE 1..11, right-to-left hardware numbering)
    // ---------------------------------------------------------------------------------------------

    // [AA021 2B-AA..4D-AG / AG4] LITE 1 (Rightmost lamp / ConsoleLampSpec #11: Y1 / O1 / OP1 / PB1 / Bit T Ctr A)
    s.lite1 =
      (s.outRegDply && s.out1) ||
      (s.dply1 && s.y1) ||
      (s.dply3 && s.oplCde1) ||
      (clockTestDply && s.btCtrA) ||
      (s.dply4 && s.pBfr1)

    // [AA023 2B-AA..4D-AG / AG4] LITE 2 (ConsoleLampSpec #10: Y2 / O2 / OP2 / PB2 / Bit T Ctr B)
    s.lite2 =
      (s.outRegDply && s.out2) ||
      (s.dply1 && s.y2) ||
      (s.dply3 && s.oplCde2) ||
      (clockTestDply && s.btCtrB) ||
      (s.dply4 && s.pBfr2)

    // [AA021 5B-AB..7D-AK / AK4] LITE 3 (ConsoleLampSpec #9: Y3 / O3 / OP3 / PB3 / Insn T Ctr A)
    s.lite3 =
      (s.outRegDply && s.out3) ||
      (s.dply1 && s.y3) ||
      (s.dply3 && s.oplCde3) ||
      (s.dply4 && s.pBfr3) ||
      (clockTestDply && s.insnTCtrA)

    // [AA023 5B-AB..7D-AK / AK4] LITE 4 (ConsoleLampSpec #8: Y4 / O4 / OP4 / PB4 / Insn T Ctr B)
    s.lite4 =
      (s.outRegDply && s.out4) ||
      (s.dply1 && s.y4) ||
      (s.dply3 && s.oplCde4) ||
      (s.dply4 && s.pBfr4) ||
      (clockTestDply && s.insnTCtrB)

    // [AA021 5J-AU..7L-AY / AY4] LITE 5 (ConsoleLampSpec #7: Y5 / O5 / OP5 / QB1 / Insn T Ctr C)
    s.lite5 =
      (clockTestDply && s.insnTCtrC) ||
      (s.dply4 && s.qBfr1) ||
      (s.dply3 && s.oplCde5) ||
      (s.dply1 && s.y5) ||
      (s.outRegDply && s.out5)

    // [AA023 5J-AU..7L-AY / AY4] LITE 6 (ConsoleLampSpec #6: Y6 / O6 / OP6 / QB2 / Insn T Ctr D)
    s.lite6 =
      (clockTestDply && s.insnTCtrD) ||
      (s.dply4 && s.qBfr2) ||
      (s.dply3 && s.oplCde6) ||
      (s.dply1 && s.y6) ||
      (s.outRegDply && s.out6)

    // [AA022 2B-AA..4D-AE / AE4] LITE 7 (ConsoleLampSpec #5: Y7 / O7 / OP7 / QB3 / CE Test Input 1)
    s.lite7 =
      (s.outRegDply && s.out7) ||
      (s.dply1 && s.y7) ||
      (s.dply3 && s.oplCde7) ||
      (s.dply4 && s.qBfr3) ||
      (clockTestDply && s.ceTestInput1)

    // [AA024 2B-AA..4D-AE / AE4] LITE 8 (ConsoleLampSpec #4, Red SETUP lens: Y8 / O8 / EQ / QB4 / CE Test Input 2)
    s.lite8 =
      (s.outRegDply && s.out8) ||
      (s.dply1 && s.y8) ||
      (s.dply3 && s.eq) ||
      (s.dply4 && s.qBfr4) ||
      (clockTestDply && s.ceTestInput2)

    // [AA022 2H-AJ..4K-AN / AN4] LITE 9 (ConsoleLampSpec #3: Y9 / BUSY / -LOW / MPX / CE Test Input 3)
    s.lite9 =
      (s.outRegDply && s.busy) ||
      (s.dply1 && s.y9) ||
      (s.dply3 && !s.low) ||
      (s.dply4 && s.mpx) ||
      (clockTestDply && s.ceTestInput3)

    // [AA024 2H-AJ..4K-AN / AN4] LITE 10 (ConsoleLampSpec #2, EOT/TYPE: Y10 / EOT / INH / RDY / CE Test Input 4)
    s.lite10 =
      ((s.dply2 || (!s.edit && s.notAid && s.autoOrStep)) && s.errorLite) ||
      (s.dply1 && s.y10) ||
      (s.dply3 && s.inh) ||
      (s.dply4 && s.rdy) ||
      (clockTestDply && s.ceTestInput4)

    // [AA021 2L-AQ..4L-AT / AT4] LITE 11 (Leftmost lamp / ConsoleLampSpec #1, RESET/HYPHEN: Y11 / MASTER RESET)
    s.lite11 =
      (s.dply1 && s.y11) ||
      s.masterReset
  }

  /**
   * Updates the `+EDIT` latch (`[AA141 5B-AD..7B-AF]`) and the `+LIGHTS` latch (`LITES`, `[AA072 1B-AB..3B-AD]`).
   */
  def updateLatches(s: BackplaneSignals): Unit = {
    evaluateSwitchAndDisplayDecodes(s)
    Ild0308DataFlow.evaluateCombinational(s)
    Ild0311OutputIo.evaluateCombinational(s)

    // [AA141 5B-AD..7B-AF] +EDIT latch samples +EDIT SWITCH synchronously at +BEGINNING PROGRAM STEP
    if (s.masterReset || s.begPrgStp) {
      s.edit = s.editSwitchContact
    }

    // [AA072 1B-AB..3B-AD] +LIGHTS latch (LITES):
    // Set at I-2, CEN BT of an OUTPUT instruction when Y8 & Y9 & Y10 (kbCde = 0111) selects Console Lights;
    // reset on Master Reset or when DESEL OUT DEV fires for another output device.
    val setLites = s.selOutDev && s.kbCde
    if (s.masterReset) {
      s.lites = false
    } else if (setLites) {
      s.lites = true
    } else if (s.deselOutDev) {
      s.lites = false
    }

    evaluateCombinational(s)
  }

  /**
   * Configures the 4 CE Display pushbutton states (`DISP 1..4`) on [[BackplaneSignals]]
   * to match a [[CeDisplayMode]] selection when in `Proc` or `Edit` switch position.
   */
  def applyDisplayModeButtons(s: BackplaneSignals, mode: CeDisplayMode): Unit = {
    s.disp1Pressed = mode == CeDisplayMode.Display1
    s.disp2Pressed = mode == CeDisplayMode.Display2
    s.disp3Pressed = mode == CeDisplayMode.Display3
    s.disp4Pressed = mode == CeDisplayMode.Display4
    evaluateCombinational(s)
  }
}
