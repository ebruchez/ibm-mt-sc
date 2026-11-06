package mtsc.cpu.ild

import mtsc.console.CeTestProbe

/**
 * Functional subsystem category for cards and cables in the **IBM MT/SC 6320 Electronic Gate
 * (`01A-A1` & `01A-B1`)**, matching **MDM Page `1201` (`BOARD DIAGRAM — ELECTRONIC GATE`)** and
 * **MDM Page `LR010` (`SOCKET LISTING`)**.
 */
enum CardCategory(val id: String, val label: String) {
  case CoreAndDrivers  extends CardCategory("core",    "Core Array & X/Y Drivers (0306–0307)")
  case SenseAndInhibit extends CardCategory("sense",   "Sense Amps, Inhibit & Mem Protect (0305, 0310)")
  case ClockAndTiming  extends CardCategory("clock",   "240 kHz Clock, Oscillator & I-Time (0303–0304)")
  case DataFlowAndAlu  extends CardCategory("alu",     "4-Bit Serial Data Flow & ALU (0308–0309)")
  case MagneticTape    extends CardCategory("mt",      "Magnetic Tape Reader Logic (0301–0302)")
  case ConsoleAndIo    extends CardCategory("io",      "Console, Lights & Translator I/O (0311–0312)")
  case OptionCard      extends CardCategory("option",  "Unpopulated / Option Slot (0313)")
  case RibbonCable     extends CardCategory("cable",   "Flat Ribbon Cable Socket (A2/A5/A6, N1..N6)")
}

/**
 * A backplane test pin and signal exposed on a physical SLD card in `BOARD B1`.
 *
 * @param pin       Backplane socket pin coordinate (e.g. `"G5D05"`, `"K6D13"`, `"E3B12"`)
 * @param name      IBM ALD/ILD signal mnemonic (e.g. `"+A"`, `"SSA"`, `"+SEL PROG STEP"`)
 * @param evaluate  Function evaluating the live logic level from [[BackplaneSignals]]
 * @param testProbe Optional corresponding [[CeTestProbe]] for one-click attachment to `CE Test 1..4`
 */
final case class CardPinSignal(
  pin: String,
  name: String,
  evaluate: BackplaneSignals => Boolean,
  testProbe: Option[CeTestProbe] = None
)

/**
 * Physical specification for one card slot or cable connector in **`BOARD B1 (CARD SIDE)`**
 * (`Columns A..N × Rows 1..7`, `I` omitted per IBM standard), from **MDM Page `1201`** and **`LR010`**.
 *
 * @param slotId      Slot coordinate in `BOARD B1` (e.g. `"G5/6"`, `"B1/2"`, `"A5"`, `"N1"`)
 * @param col         Column letter (`"A"`, `"B"`, `"C"`, `"D"`, `"E"`, `"F"`, `"G"`, `"H"`, `"J"`, `"K"`, `"L"`, `"M"`, `"N"`)
 * @param rowStart    Top row occupied (`1..7`)
 * @param rowSpan     Height in cage rows (`1` for single card / socket, `2` for double card)
 * @param title       Card title printed on **MDM Page `1201`** (e.g. `"CLOCK"`, `"DATA FLOW 1"`, `"SENSE INH 1"`)
 * @param cardNumber  4-digit card assembly suffix (prefixed by `580`, e.g. `"6458"` -> `5806458`, `"3859"` -> `5803859`)
 * @param ecNumber    Engineering Change number from `MDM Page 1201` (e.g. `"172414B"`)
 * @param aldSheets   Automated Logic Diagram sheets implemented on this card (e.g. `Vector("AA081", "AA082", "AA083")`)
 * @param ildSheets   Intermediate Logic Diagram sheets (e.g. `Vector("0303")`)
 * @param category    Functional subsystem category
 * @param description Hardware summary of the gates, latches, or cables on this card
 * @param installed   Whether this card is populated in the standard MT/SC 6320 configuration
 * @param isActive    Evaluates whether the card has active switching / selected logic in the current cycle
 * @param pins        Representative backplane test pins and signals on this card
 */
final case class BackplaneCardSpec(
  slotId: String,
  col: String,
  rowStart: Int,
  rowSpan: Int,
  title: String,
  cardNumber: String,
  ecNumber: String,
  aldSheets: Vector[String],
  ildSheets: Vector[String],
  category: CardCategory,
  description: String,
  installed: Boolean = true,
  isActive: BackplaneSignals => Boolean,
  pins: Vector[CardPinSignal]
) {
  def fullPartNumber: String =
    if (cardNumber.forall(_.isDigit) && cardNumber.nonEmpty) s"580$cardNumber" else cardNumber
}

/**
 * Complete hardware catalog of **`BOARD A1`** (3D Core Memory & Diode Steering Board) and
 * **`BOARD B1`** (13-Column `A..N` × 7-Row `1..7` SLD Logic Card Cage) from **MDM Page `1201`**
 * and **Socket Listing `LR010`**.
 */
object BackplaneCardCage {

  /** The 13 physical column letters of `BOARD B1` (`I` is skipped per IBM convention). */
  val Columns: Vector[String] = Vector("A", "B", "C", "D", "E", "F", "G", "H", "J", "K", "L", "M", "N")

  /** The 7 physical row numbers of `BOARD B1`. */
  val Rows: Vector[Int] = (1 to 7).toVector

  /** All populated cards and cable sockets in `BOARD B1 (01A-B1)`. */
  val AllCards: Vector[BackplaneCardSpec] = Vector(
    // =============================================================================================
    // COLUMN A: Core Sense Cable, Memory Ribbon Cables (A5/A6), and GMS X/Y Current Drivers
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "A2",
      col = "A",
      rowStart = 1,
      rowSpan = 2,
      title = "SENSE CABLE",
      cardNumber = "CABLE",
      ecNumber = "1202492",
      aldSheets = Vector("BA011"),
      ildSheets = Vector("0305", "0310"),
      category = CardCategory.RibbonCable,
      description = "Shielded sense/inhibit interconnect from the 4-plane 3D Core Array (BOARD A1) to Sense Inhibit Cards B1/2 and C1/2.",
      isActive = s => s.readGate || s.csYLog,
      pins = Vector(
        CardPinSignal("A2B02", "SENSE LINE EV 1", _.senseInhLineEv1),
        CardPinSignal("A2D02", "SENSE LINE ODD 1", _.senseInhLineOdd1),
        CardPinSignal("A2B04", "SENSE LINE EV 2", _.senseInhLineEv2),
        CardPinSignal("A2D04", "SENSE LINE ODD 2", _.senseInhLineOdd2),
        CardPinSignal("A2B07", "SENSE LINE EV 3", _.senseInhLineEv3),
        CardPinSignal("A2D07", "SENSE LINE ODD 3", _.senseInhLineOdd3),
        CardPinSignal("A2B09", "SENSE LINE EV 4", _.senseInhLineEv4),
        CardPinSignal("A2D09", "SENSE LINE ODD 4", _.senseInhLineOdd4)
      )
    ),
    BackplaneCardSpec(
      slotId = "A4",
      col = "A",
      rowStart = 4,
      rowSpan = 1,
      title = "GMS X-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA272"),
      ildSheets = Vector("0307"),
      category = CardCategory.CoreAndDrivers,
      description = "4-Group Memory Switch (X-Direction): high-current transistor switches for X-Switches 5..8.",
      isActive = s => s.activeXSwitch >= 4,
      pins = Vector(
        CardPinSignal("A4A2", "X-SWITCH 5", _.activeXSwitch == 4),
        CardPinSignal("A4A5", "X-SWITCH 6", _.activeXSwitch == 5),
        CardPinSignal("A4A8", "X-SWITCH 7", _.activeXSwitch == 6),
        CardPinSignal("A4AA", "X-SWITCH 8 (SPEC)", _.activeXSwitch == 7)
      )
    ),
    BackplaneCardSpec(
      slotId = "A5",
      col = "A",
      rowStart = 5,
      rowSpan = 1,
      title = "MEMORY CABLE",
      cardNumber = "5049",
      ecNumber = "1202490",
      aldSheets = Vector("AA274"),
      ildSheets = Vector("0306", "0307"),
      category = CardCategory.RibbonCable,
      description = "Upper yellow flat ribbon cable connecting BOARD B1 X/Y current drivers to the BOARD A1 Core Memory Diode Board.",
      isActive = s => s.csYLog || s.csXLog,
      pins = Vector(
        CardPinSignal("A5A1", "X-DRV / X-SW BUS", s => s.csXLog || s.csYLog),
        CardPinSignal("A5B1", "REWO (EVEN)", !_.btCtrA),
        CardPinSignal("A5C1", "ROWE (ODD)", _.btCtrA)
      )
    ),
    BackplaneCardSpec(
      slotId = "A6",
      col = "A",
      rowStart = 6,
      rowSpan = 1,
      title = "MEMORY CABLE",
      cardNumber = "5050",
      ecNumber = "1202491",
      aldSheets = Vector("AA275"),
      ildSheets = Vector("0306", "0307"),
      category = CardCategory.RibbonCable,
      description = "Lower yellow flat ribbon cable connecting BOARD B1 Y-Direction drivers/switches to the BOARD A1 Core Memory Diode Board.",
      isActive = s => s.csYLog,
      pins = Vector(
        CardPinSignal("A6A1", "Y-DRV / Y-SW BUS", _.csYLog),
        CardPinSignal("A6B1", "SEL MEM SPEC", _.selMemSpec),
        CardPinSignal("A6C1", "SEL MEM UP", _.selMemUp, Some(CeTestProbe.SelMemUp))
      )
    ),
    BackplaneCardSpec(
      slotId = "A7",
      col = "A",
      rowStart = 7,
      rowSpan = 1,
      title = "GMS Y-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA273"),
      ildSheets = Vector("0306"),
      category = CardCategory.CoreAndDrivers,
      description = "3-Group Memory Switch (Y-Direction): high-current transistor switches for Y-Switches 1..4.",
      isActive = s => s.csYLog,
      pins = Vector(
        CardPinSignal("A7A2", "Y-SWITCH 1", _.activeYSwitch == 0),
        CardPinSignal("A7A5", "Y-SWITCH 2", _.activeYSwitch == 1),
        CardPinSignal("A7A8", "Y-SWITCH 3", _.activeYSwitch == 2),
        CardPinSignal("A7AA", "Y-SWITCH 4", _.activeYSwitch == 3)
      )
    ),

    // =============================================================================================
    // COLUMN B: Sense Inhibit 1 (Planes 1 & 2) and GMS X/Y Current Drivers
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "B1/2",
      col = "B",
      rowStart = 1,
      rowSpan = 2,
      title = "SENSE INH 1",
      cardNumber = "6476",
      ecNumber = "169339A",
      aldSheets = Vector("AA261"),
      ildSheets = Vector("0305", "0310"),
      category = CardCategory.SenseAndInhibit,
      description = "Sense Amplifiers, Sense Strobe Gates, Q-Buffer Latches (1 & 2), and Core Write Inhibit Drivers for Bit Planes 1 & 2.",
      isActive = s => s.readGate || s.qBfr1 || s.qBfr2 || s.senseInhLineEv1 || s.senseInhLineEv2,
      pins = Vector(
        CardPinSignal("B1A2", "+Q BFR 1", _.qBfr1),
        CardPinSignal("B1A5", "+Q BFR 2", _.qBfr2),
        CardPinSignal("B2A7", "INH LINE EV 1", _.senseInhLineEv1),
        CardPinSignal("B2A8", "INH LINE ODD 1", _.senseInhLineOdd1),
        CardPinSignal("B2A9", "INH LINE EV 2", _.senseInhLineEv2),
        CardPinSignal("B2AA", "INH LINE ODD 2", _.senseInhLineOdd2)
      )
    ),
    BackplaneCardSpec(
      slotId = "B5",
      col = "B",
      rowStart = 5,
      rowSpan = 1,
      title = "GMS X-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA271"),
      ildSheets = Vector("0307"),
      category = CardCategory.CoreAndDrivers,
      description = "4-Group Memory Switch (X-Direction): current drivers for X-Drivers 1..4.",
      isActive = s => s.activeXDriver < 4,
      pins = Vector(
        CardPinSignal("B5A2", "X-DRIVER 1", _.activeXDriver == 0),
        CardPinSignal("B5A5", "X-DRIVER 2", _.activeXDriver == 1),
        CardPinSignal("B5A8", "X-DRIVER 3", _.activeXDriver == 2),
        CardPinSignal("B5AA", "X-DRIVER 4", _.activeXDriver == 3)
      )
    ),
    BackplaneCardSpec(
      slotId = "B6",
      col = "B",
      rowStart = 6,
      rowSpan = 1,
      title = "GMS Y-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA273"),
      ildSheets = Vector("0306"),
      category = CardCategory.CoreAndDrivers,
      description = "3-Group Memory Switch (Y-Direction): current drivers for Y-Drivers 1..4 (Lower 1K Core Bank).",
      isActive = s => s.activeYDriver < 4,
      pins = Vector(
        CardPinSignal("B6A2", "Y-DRIVER 1", _.activeYDriver == 0),
        CardPinSignal("B6A5", "Y-DRIVER 2", _.activeYDriver == 1),
        CardPinSignal("B6A8", "Y-DRIVER 3", _.activeYDriver == 2),
        CardPinSignal("B6AA", "Y-DRIVER 4", _.activeYDriver == 3)
      )
    ),
    BackplaneCardSpec(
      slotId = "B7",
      col = "B",
      rowStart = 7,
      rowSpan = 1,
      title = "GMS Y-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA273"),
      ildSheets = Vector("0306"),
      category = CardCategory.CoreAndDrivers,
      description = "3-Group Memory Switch (Y-Direction): current drivers for Y-Drivers 5..8 (Upper 1K Core Bank & Special Words).",
      isActive = s => s.activeYDriver >= 4,
      pins = Vector(
        CardPinSignal("B7A2", "Y-DRIVER 5", _.activeYDriver == 4),
        CardPinSignal("B7A5", "Y-DRIVER 6 (B-WD)", _.activeYDriver == 5),
        CardPinSignal("B7A8", "Y-DRIVER 7 (A-WD)", _.activeYDriver == 6),
        CardPinSignal("B7AA", "Y-DRIVER 8 (PSAR)", _.activeYDriver == 7)
      )
    ),

    // =============================================================================================
    // COLUMN C: Sense Inhibit 2 (Planes 3 & 4), GMS X-Dir, and Memory Address Y-Selection
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "C1/2",
      col = "C",
      rowStart = 1,
      rowSpan = 2,
      title = "SENSE INH 2",
      cardNumber = "6476",
      ecNumber = "169339A",
      aldSheets = Vector("AA262"),
      ildSheets = Vector("0305", "0310"),
      category = CardCategory.SenseAndInhibit,
      description = "Sense Amplifiers, Sense Strobe Gates, Q-Buffer Latches (3 & 4), and Core Write Inhibit Drivers for Bit Planes 3 & 4.",
      isActive = s => s.readGate || s.qBfr3 || s.qBfr4 || s.senseInhLineEv3 || s.senseInhLineEv4,
      pins = Vector(
        CardPinSignal("C1A2", "+Q BFR 3", _.qBfr3),
        CardPinSignal("C1A5", "+Q BFR 4", _.qBfr4),
        CardPinSignal("C2A7", "INH LINE EV 3", _.senseInhLineEv3),
        CardPinSignal("C2A8", "INH LINE ODD 3", _.senseInhLineOdd3),
        CardPinSignal("C2A9", "INH LINE EV 4", _.senseInhLineEv4),
        CardPinSignal("C2AA", "INH LINE ODD 4", _.senseInhLineOdd4)
      )
    ),
    BackplaneCardSpec(
      slotId = "C5",
      col = "C",
      rowStart = 5,
      rowSpan = 1,
      title = "GMS X-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA271"),
      ildSheets = Vector("0307"),
      category = CardCategory.CoreAndDrivers,
      description = "4-Group Memory Switch (X-Direction): current drivers for X-Drivers 5..8.",
      isActive = s => s.activeXDriver >= 4,
      pins = Vector(
        CardPinSignal("C5A2", "X-DRIVER 5", _.activeXDriver == 4),
        CardPinSignal("C5A5", "X-DRIVER 6", _.activeXDriver == 5),
        CardPinSignal("C5A8", "X-DRIVER 7", _.activeXDriver == 6),
        CardPinSignal("C5AA", "X-DRIVER 8 (SPEC)", _.activeXDriver == 7)
      )
    ),
    BackplaneCardSpec(
      slotId = "C6/7",
      col = "C",
      rowStart = 6,
      rowSpan = 2,
      title = "MEM ADDR REG Y-SEL",
      cardNumber = "6461",
      ecNumber = "169326A",
      aldSheets = Vector("AA111", "AA112"),
      ildSheets = Vector("0306"),
      category = CardCategory.CoreAndDrivers,
      description = "Memory Address Latches Y1, Y2, Y3, Y7, Y11 and Y-Direction Driver/Switch Pre-Decoders.",
      isActive = s => s.y1 || s.y2 || s.y3 || s.y7 || s.y11 || s.selMemSpec,
      pins = Vector(
        CardPinSignal("C6B12", "+SEL MEM SPEC", _.selMemSpec),
        CardPinSignal("C6D02", "+Y1 (1)", _.y1),
        CardPinSignal("C6D05", "+Y2 (2)", _.y2),
        CardPinSignal("C6D08", "+Y3 (4)", _.y3),
        CardPinSignal("C7B04", "+Y7 (64)", _.y7),
        CardPinSignal("C7B09", "+Y11 (1024)", _.y11),
        CardPinSignal("C7D11", "+DCD DRVR Y 5-6-7-8", _.dcdDrvrY5678)
      )
    ),

    // =============================================================================================
    // COLUMN D: Memory Address X-Selection, GMS X-Dir, and Memory Control Card
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "D3/4",
      col = "D",
      rowStart = 3,
      rowSpan = 2,
      title = "MEM ADDR REG X-SEL",
      cardNumber = "6462",
      ecNumber = "169455B",
      aldSheets = Vector("AA121", "AA122"),
      ildSheets = Vector("0307"),
      category = CardCategory.CoreAndDrivers,
      description = "Memory Address Latches Y4, Y5, Y6, Y8, Y9, Y10 and X-Direction Driver/Switch Pre-Decoders + REWO/ROWE.",
      isActive = s => s.y4 || s.y5 || s.y6 || s.y8 || s.y9 || s.y10 || s.rewo || s.rowe,
      pins = Vector(
        CardPinSignal("D3D09", "+REWO", _.rewo),
        CardPinSignal("D3D05", "+ROWE", _.rowe),
        CardPinSignal("D3B02", "+Y4 (8)", _.y4),
        CardPinSignal("D3B05", "+Y5 (16)", _.y5),
        CardPinSignal("D3B08", "+Y6 (32)", _.y6),
        CardPinSignal("D4B02", "+Y8 (128)", _.y8),
        CardPinSignal("D4B05", "+Y9 (256)", _.y9),
        CardPinSignal("D4B08", "+Y10 (512)", _.y10)
      )
    ),
    BackplaneCardSpec(
      slotId = "D5",
      col = "D",
      rowStart = 5,
      rowSpan = 1,
      title = "GMS X-DIR",
      cardNumber = "3859",
      ecNumber = "167932A",
      aldSheets = Vector("AA272"),
      ildSheets = Vector("0307"),
      category = CardCategory.CoreAndDrivers,
      description = "4-Group Memory Switch (X-Direction): high-current transistor switches for X-Switches 1..4.",
      isActive = s => s.activeXSwitch < 4,
      pins = Vector(
        CardPinSignal("D5A2", "X-SWITCH 1", _.activeXSwitch == 0),
        CardPinSignal("D5A5", "X-SWITCH 2", _.activeXSwitch == 1),
        CardPinSignal("D5A8", "X-SWITCH 3", _.activeXSwitch == 2),
        CardPinSignal("D5AA", "X-SWITCH 4", _.activeXSwitch == 3)
      )
    ),
    BackplaneCardSpec(
      slotId = "D6/7",
      col = "D",
      rowStart = 6,
      rowSpan = 2,
      title = "MEM CTL",
      cardNumber = "6474",
      ecNumber = "169338A",
      aldSheets = Vector("AA241"),
      ildSheets = Vector("0305", "0306", "0307"),
      category = CardCategory.SenseAndInhibit,
      description = "Memory Control Card: Read Gate Strobe, Current Source Timing Gates, and Sense Amplifier Bias.",
      isActive = s => s.readGate || s.csYLog || s.csXLog,
      pins = Vector(
        CardPinSignal("D6B02", "+READ GATE", _.readGate, Some(CeTestProbe.ReadGate)),
        CardPinSignal("D6B08", "+SELECT MEMORY", _.selectMemory),
        CardPinSignal("D7D04", "-KILL CS Y UPPER BYTE", !_.killCsYUpperByte)
      )
    ),

    // =============================================================================================
    // COLUMN E: Word Selection and Voltage Reference / Memory Protect
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "E3/4",
      col = "E",
      rowStart = 3,
      rowSpan = 2,
      title = "WORD SEL",
      cardNumber = "6454",
      ecNumber = "169449B",
      aldSheets = Vector("AA041", "AA042"),
      ildSheets = Vector("0304", "0306"),
      category = CardCategory.CoreAndDrivers,
      description = "Word Selection Logic: selects PSAR, A-Word, B-Word, or Upper/Lower Core Storage based on Instruction Time & Opcode.",
      isActive = s => s.selProgStepAddr || s.selAWd || s.selBWd || s.selMemUp,
      pins = Vector(
        CardPinSignal("E3B12", "+SEL PROG STEP", _.selProgStepAddr, Some(CeTestProbe.SelProgStep)),
        CardPinSignal("E3B11", "+SEL A WD", _.selAWd, Some(CeTestProbe.SelAWd)),
        CardPinSignal("E3B07", "+SEL B WD", _.selBWd, Some(CeTestProbe.SelBWd)),
        CardPinSignal("E4B02", "+SEL MEM UP", _.selMemUp, Some(CeTestProbe.SelMemUp))
      )
    ),
    BackplaneCardSpec(
      slotId = "E5/6",
      col = "E",
      rowStart = 5,
      rowSpan = 2,
      title = "VOLT REF & MEM PROTECT",
      cardNumber = "6465",
      ecNumber = "169453B",
      aldSheets = Vector("AA151"),
      ildSheets = Vector("0305"),
      category = CardCategory.SenseAndInhibit,
      description = "Voltage Reference, Power-On Sequencing, +MASTER RESET Generator, and Core Memory Power-Fault Inhibit.",
      isActive = s => !s.inhibitMemory || s.masterReset,
      pins = Vector(
        CardPinSignal("E5B01", "+MASTER RESET", _.masterReset),
        CardPinSignal("E5D04", "-INHIBIT MEMORY", !_.inhibitMemory),
        CardPinSignal("E6B09", "+POWER ON READY", !_.inhibitMemory)
      )
    ),

    // =============================================================================================
    // COLUMN F: Magnetic Tape Counter, Data Flow 1 (Bits 1-2), and Instruction Time Counter
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "F1/2",
      col = "F",
      rowStart = 1,
      rowSpan = 2,
      title = "MT CTR",
      cardNumber = "6469",
      ecNumber = "169333A",
      aldSheets = Vector("AA191", "AA192", "AA193"),
      ildSheets = Vector("0301", "0302"),
      category = CardCategory.MagneticTape,
      description = "Magnetic Tape 5-Bit Shift Counter (MT CTR A..E) and Count 0..10, 13 (Unload), 14 (Search), 15 (Load) Decoders.",
      isActive = s => s.mtCounterBits != 0x10 || s.mtRd || s.mtSch,
      pins = Vector(
        CardPinSignal("F1B02", "+MT CTR A", _.mtCtrA),
        CardPinSignal("F1B05", "+MT CTR B", _.mtCtrB),
        CardPinSignal("F1B08", "+MT CTR C", _.mtCtrC),
        CardPinSignal("F1B11", "+MT CTR D", _.mtCtrD),
        CardPinSignal("F2B02", "+MT CTR E", _.mtCtrE),
        CardPinSignal("F2D06", "+MT 10 COUNT", _.mtTenCount, Some(CeTestProbe.MtTenCount)),
        CardPinSignal("F2D10", "+MT COUNT 14 (SCH)", _.mtCount14),
        CardPinSignal("F2D12", "+MT COUNT 15 (LOAD)", _.mtCount15)
      )
    ),
    BackplaneCardSpec(
      slotId = "F3/4",
      col = "F",
      rowStart = 3,
      rowSpan = 2,
      title = "DATA FLOW 1",
      cardNumber = "6451",
      ecNumber = "169043A",
      aldSheets = Vector("AA011", "AA012"),
      ildSheets = Vector("0308"),
      category = CardCategory.DataFlowAndAlu,
      description = "Lower 2 Bits of 4-Bit Serial Data Path: P-Buffer 1 & 2, Serial Full Adder / Subtractor ACC 1 & 2, and Carry 1 & 2.",
      isActive = s => s.pBfr1 || s.pBfr2 || s.acc1 || s.acc2 || s.car1 || s.car2,
      pins = Vector(
        CardPinSignal("F3B02", "+P BFR 1", _.pBfr1),
        CardPinSignal("F3B08", "+P BFR 2", _.pBfr2),
        CardPinSignal("F3D04", "+ACC 1", _.acc1),
        CardPinSignal("F4D04", "+ACC 2", _.acc2),
        CardPinSignal("F4B06", "+CARRY 1", _.car1),
        CardPinSignal("F4B12", "+CARRY 2", _.car2)
      )
    ),
    BackplaneCardSpec(
      slotId = "F5/6",
      col = "F",
      rowStart = 5,
      rowSpan = 2,
      title = "INSN TIME CTR",
      cardNumber = "6459",
      ecNumber = "169051A",
      aldSheets = Vector("AA091", "AA092"),
      ildSheets = Vector("0304"),
      category = CardCategory.ClockAndTiming,
      description = "Instruction Time Counter (I-0..I-9): Indicator Latches (IA..ID), Steering Latches, P-Only, Permute & Set-9 Logic.",
      isActive = _ => true,
      pins = Vector(
        CardPinSignal("F5D04", "+P ONLY", _.pOnly, Some(CeTestProbe.POnly)),
        CardPinSignal("F5B02", "+INSN T CTR A (IA)", _.insnTCtrA),
        CardPinSignal("F5B05", "+INSN T CTR B (IB)", _.insnTCtrB),
        CardPinSignal("F5B08", "+INSN T CTR C (IC)", _.insnTCtrC),
        CardPinSignal("F5B11", "+INSN T CTR D (ID)", _.insnTCtrD),
        CardPinSignal("F6B04", "+PERMUTE", _.prmt, Some(CeTestProbe.Prmt)),
        CardPinSignal("F6B09", "+SET 9", _.set9, Some(CeTestProbe.Set9)),
        CardPinSignal("F6D06", "+RESET IND", _.resInds)
      )
    ),

    // =============================================================================================
    // COLUMN G: Magnetic Tape Data Registers, Data Flow 2 (Bits 3-4), and 240 kHz Clock
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "G1/2",
      col = "G",
      rowStart = 1,
      rowSpan = 2,
      title = "MT DATA REG",
      cardNumber = "6471",
      ecNumber = "169335A",
      aldSheets = Vector("AA211", "AA212"),
      ildSheets = Vector("0302"),
      category = CardCategory.MagneticTape,
      description = "Magnetic Tape 9-Bit Data Latches (DL 1..9), 3-Group Odd Parity Checkers (X/Y/Z Parity), and Input Data Bus Strobe.",
      isActive = s => s.mtDataLatchesMask != 0 || s.charRdy || s.setLo,
      pins = Vector(
        CardPinSignal("G1B02", "+MT DATA 1", _.mtData1),
        CardPinSignal("G1B05", "+MT DATA 2", _.mtData2),
        CardPinSignal("G1B08", "+MT DATA 3", _.mtData3),
        CardPinSignal("G1B11", "+MT DATA 4", _.mtData4),
        CardPinSignal("G2B02", "+MT DATA 8 (SW)", _.mtData8),
        CardPinSignal("G2B05", "+MT DATA 9 (LC)", _.mtData9),
        CardPinSignal("G2D08", "+MT ODD PARITY", _.mtOddParity, Some(CeTestProbe.MtOddParity)),
        CardPinSignal("G2D11", "+MT SET LO (SKIP)", _.setLo, Some(CeTestProbe.MtSetLo))
      )
    ),
    BackplaneCardSpec(
      slotId = "G3/4",
      col = "G",
      rowStart = 3,
      rowSpan = 2,
      title = "DATA FLOW 2",
      cardNumber = "6451",
      ecNumber = "169043A",
      aldSheets = Vector("AA013", "AA014"),
      ildSheets = Vector("0308"),
      category = CardCategory.DataFlowAndAlu,
      description = "Upper 2 Bits of 4-Bit Serial Data Path: P-Buffer 3 & 4, Serial Full Adder / Subtractor ACC 3 & 4, and Carry 3 & 4.",
      isActive = s => s.pBfr3 || s.pBfr4 || s.acc3 || s.acc4 || s.car3 || s.car4,
      pins = Vector(
        CardPinSignal("G3B02", "+P BFR 3", _.pBfr3),
        CardPinSignal("G3B08", "+P BFR 4", _.pBfr4),
        CardPinSignal("G3D04", "+ACC 3", _.acc3),
        CardPinSignal("G4D04", "+ACC 4", _.acc4),
        CardPinSignal("G4B06", "+CARRY 3", _.car3),
        CardPinSignal("G4B12", "+CARRY 4", _.car4, Some(CeTestProbe.Car4))
      )
    ),
    BackplaneCardSpec(
      slotId = "G5/6",
      col = "G",
      rowStart = 5,
      rowSpan = 2,
      title = "CLOCK",
      cardNumber = "6458",
      ecNumber = "172414B",
      aldSheets = Vector("AA081", "AA082", "AA083"),
      ildSheets = Vector("0303"),
      category = CardCategory.ClockAndTiming,
      description = "Master Quadrature & Trinary Clock (+A, +B, +TWR, +P TIME, +CEN BT), Bit Time Counter (B1-4..B13-16), and CS-X/Y Logic.",
      isActive = _ => true,
      pins = Vector(
        CardPinSignal("G5D05", "+A", _.a),
        CardPinSignal("G5B08", "+B", _.b),
        CardPinSignal("G5B03", "+TWR", _.twr, Some(CeTestProbe.Twr)),
        CardPinSignal("G6B11", "+P TIME", _.pTime, Some(CeTestProbe.PTime)),
        CardPinSignal("G5D11", "+CEN BIT T", _.cenBt, Some(CeTestProbe.CenBt)),
        CardPinSignal("G6B02", "+BMP STRB T", _.bmpStrbT),
        CardPinSignal("G6B13", "+STR LTH STRB", _.strLthStrobe),
        CardPinSignal("G6D05", "CURR SRC Y LOG", _.csYLog)
      )
    ),

    // =============================================================================================
    // COLUMN H: Magnetic Tape Magnet Logic, Read/Write Control, and Register Control
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "H1/2",
      col = "H",
      rowStart = 1,
      rowSpan = 2,
      title = "MT MAG LOGIC",
      cardNumber = "6470",
      ecNumber = "169334A",
      aldSheets = Vector("AA201", "AA202"),
      ildSheets = Vector("0301", "0302"),
      category = CardCategory.MagneticTape,
      description = "Magnetic Tape Deck Transport Control: Station Right/Left, Increment, Read, Search, Cycle Clutch & Stepping Magnets.",
      isActive = s => s.mtStaRight || s.mtStaLeft || s.mtIncr || s.mtRd || s.mtSch,
      pins = Vector(
        CardPinSignal("H1B02", "+MT STA RIGHT", _.mtStaRight, Some(CeTestProbe.MtStaRight)),
        CardPinSignal("H1B05", "+MT STA LEFT", _.mtStaLeft),
        CardPinSignal("H1B09", "+MT INCR", _.mtIncr, Some(CeTestProbe.MtIncr)),
        CardPinSignal("H1D04", "+MT RD", _.mtRd),
        CardPinSignal("H2B04", "+MT SCH", _.mtSch),
        CardPinSignal("H2D06", "+MT CARR RET", _.mtCarRet, Some(CeTestProbe.MtCarRet)),
        CardPinSignal("H2D10", "+MT CYC CLUTCH", _.mtCycClutch),
        CardPinSignal("H2D12", "+MT STEP MAGS", _.mtStepMags)
      )
    ),
    BackplaneCardSpec(
      slotId = "H3/4",
      col = "H",
      rowStart = 3,
      rowSpan = 2,
      title = "RD/WR CTL",
      cardNumber = "6456",
      ecNumber = "169048A",
      aldSheets = Vector("AA061"),
      ildSheets = Vector("0304", "0305", "0308", "0310"),
      category = CardCategory.SenseAndInhibit,
      description = "Read/Write & ALU Mode Control: generates +WR ACCUM, +WR P BFR, +INH READ, +ADD/-ADD, and Instruction Decode classes.",
      isActive = s => s.wrAcc || s.wrPBfr || s.inhRd || s.athOp,
      pins = Vector(
        CardPinSignal("H3D07", "+WR ACCUM", _.wrAcc, Some(CeTestProbe.WrAcc)),
        CardPinSignal("H3D13", "+WR P BFR", _.wrPBfr, Some(CeTestProbe.WrPBfr)),
        CardPinSignal("H4B02", "+ADD", _.add, Some(CeTestProbe.Add)),
        CardPinSignal("H4B06", "+ATH OP", _.athOp),
        CardPinSignal("H4D04", "+IMMEDIATE", _.immediate),
        CardPinSignal("H4D09", "+JUMP", _.jmp)
      )
    ),
    BackplaneCardSpec(
      slotId = "H5/6",
      col = "H",
      rowStart = 5,
      rowSpan = 2,
      title = "REG CTL",
      cardNumber = "6460",
      ecNumber = "169052A",
      aldSheets = Vector("AA101"),
      ildSheets = Vector("0306", "0308", "0309"),
      category = CardCategory.DataFlowAndAlu,
      description = "Register & Carry Control: Y-Latch Load/Reset Strobes, Carry-Over Indicator & Steering Latch, and Bit-Test Sampling.",
      isActive = s => s.ldY || s.carOver || s.accEqualZero || s.samBt,
      pins = Vector(
        CardPinSignal("H5B09", "+LOAD Y", s => s.ldY || s.ldYInsnT6),
        CardPinSignal("H5D02", "+INH READ", _.inhRd, Some(CeTestProbe.InhRd)),
        CardPinSignal("H5D09", "+CARRY OVER", _.carOver, Some(CeTestProbe.CarOver)),
        CardPinSignal("H6B04", "+ACC EQUAL ZERO", _.accEqualZero, Some(CeTestProbe.AccEqZero)),
        CardPinSignal("H6D06", "+SAMPLED BIT TIME", _.samBt, Some(CeTestProbe.SamBt)),
        CardPinSignal("H6D11", "+SAMPLED BIT POS", _.samBPos, Some(CeTestProbe.SamBPos))
      )
    ),

    // =============================================================================================
    // COLUMN J: CPU Integrator, Input/Output Control, and Output & OPL CDE Registers
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "J1/2",
      col = "J",
      rowStart = 1,
      rowSpan = 2,
      title = "CPU INTEG",
      cardNumber = "6472",
      ecNumber = "169447B",
      aldSheets = Vector("AA221"),
      ildSheets = Vector("0303", "0308", "0312"),
      category = CardCategory.ConsoleAndIo,
      description = "Control Panel Integrators: contact-bounce RC filters and Schmitt triggers for Console Pushbuttons and Slide Switches.",
      isActive = s => s.kbCodeRdy || s.stepButtonPressed || s.edit,
      pins = Vector(
        CardPinSignal("J1B02", "+AUTO OR STEP", _.autoOrStep),
        CardPinSignal("J1B06", "+STEP 1", _.step1),
        CardPinSignal("J1B09", "+STEP 2", _.step2),
        CardPinSignal("J2D04", "+KB CODE RDY", _.kbCodeRdy)
      )
    ),
    BackplaneCardSpec(
      slotId = "J3/4",
      col = "J",
      rowStart = 3,
      rowSpan = 2,
      title = "I/O",
      cardNumber = "6464",
      ecNumber = "172413C",
      aldSheets = Vector("AA141", "AA142"),
      ildSheets = Vector("0311", "0312"),
      category = CardCategory.ConsoleAndIo,
      description = "Input/Output Control Latches: +MPX, +PRE INH, +INH, +RDY, +BSY, +TSLTR SEL, +LITES, and +EDIT Mode Latch.",
      isActive = s => s.mpx || s.inh || s.busy || s.tsltrSel || s.lites || s.edit,
      pins = Vector(
        CardPinSignal("J3B02", "+MPX", _.mpx),
        CardPinSignal("J3B05", "+INHIBIT (INH)", _.inh),
        CardPinSignal("J3B08", "+READY (RDY)", _.rdy),
        CardPinSignal("J3B11", "+BUSY (BSY)", _.busy),
        CardPinSignal("J4B04", "+TSLTR SEL", _.tsltrSel, Some(CeTestProbe.TsltrSel)),
        CardPinSignal("J4B08", "+LITES SEL", _.lites, Some(CeTestProbe.Lites)),
        CardPinSignal("J4D10", "+EDIT LATCH", _.edit)
      )
    ),
    BackplaneCardSpec(
      slotId = "J5/6",
      col = "J",
      rowStart = 5,
      rowSpan = 2,
      title = "OUTPUT & OPL CDE REG",
      cardNumber = "6453",
      ecNumber = "169045A",
      aldSheets = Vector("AA031", "AA032"),
      ildSheets = Vector("0309", "0311"),
      category = CardCategory.DataFlowAndAlu,
      description = "8-Bit Output Register (OUT 1..8) and 7-Bit Operational Code Register (OPL CDE 1..7) + Translator Function Decodes.",
      isActive = s => s.outputByte != 0 || s.oplCde1 || s.oplCde2 || s.oplCde3 || s.oplCde4,
      pins = Vector(
        CardPinSignal("J5B02", "+OPL CDE 1 (MSB)", _.oplCde1),
        CardPinSignal("J5B04", "+OPL CDE 2", _.oplCde2),
        CardPinSignal("J5B06", "+OPL CDE 3", _.oplCde3),
        CardPinSignal("J5B08", "+OPL CDE 4", _.oplCde4),
        CardPinSignal("J5B10", "+OPL CDE 5", _.oplCde5),
        CardPinSignal("J6B02", "+OPL CDE 6", _.oplCde6),
        CardPinSignal("J6B04", "+OPL CDE 7 (LSB)", _.oplCde7),
        CardPinSignal("J6D08", "+OUT REG NON-ZERO", _.outputByte != 0)
      )
    ),

    // =============================================================================================
    // COLUMN K: Magnetic Tape Integrator, Console Lights 1, and 240 kHz Oscillator / Low-Equal
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "K1/2",
      col = "K",
      rowStart = 1,
      rowSpan = 2,
      title = "MT INTEG",
      cardNumber = "6472",
      ecNumber = "169447B",
      aldSheets = Vector("AA222"),
      ildSheets = Vector("0301", "0302"),
      category = CardCategory.MagneticTape,
      description = "Magnetic Tape Reader Integrators: debounce & signal conditioning for MPS A/B emitter pulses, HOME cam, and MT buttons.",
      isActive = s => s.mpsA || s.mpsB || !s.mtHome || s.mtLdRt || s.mtLdLf || s.mtSchKey,
      pins = Vector(
        CardPinSignal("K1B02", "+MPS A", _.mpsA),
        CardPinSignal("K1B05", "+MPS B", _.mpsB),
        CardPinSignal("K1B09", "+MT HOME", _.mtHome),
        CardPinSignal("K2D04", "+MT DNT OUT", _.mtDntOut)
      )
    ),
    BackplaneCardSpec(
      slotId = "K3/4",
      col = "K",
      rowStart = 3,
      rowSpan = 2,
      title = "LITES 1",
      cardNumber = "6452",
      ecNumber = "169044A",
      aldSheets = Vector("AA021", "AA022"),
      ildSheets = Vector("0312"),
      category = CardCategory.ConsoleAndIo,
      description = "Console Indicator Lamp Multiplexer 1: drives Lamps 1 (RESET), 2 (EOT/INH/TEST 4), 3 (BSY/-LOW/RDY/TEST 3), 10, and 11.",
      isActive = s => s.lite1 || s.lite2 || s.lite3 || s.lite10 || s.lite11,
      pins = Vector(
        CardPinSignal("K3B02", "LAMP 1 (RESET)", _.lite1),
        CardPinSignal("K3B05", "LAMP 2 (Y11/EOT/T4)", _.lite2),
        CardPinSignal("K3B08", "LAMP 3 (Y10/BSY/T3)", _.lite3),
        CardPinSignal("K4B06", "LAMP 10 (Y2/O2/PB2)", _.lite10),
        CardPinSignal("K4B09", "LAMP 11 (Y1/O1/PB1)", _.lite11)
      )
    ),
    BackplaneCardSpec(
      slotId = "K5/6",
      col = "K",
      rowStart = 5,
      rowSpan = 2,
      title = "OSC",
      cardNumber = "6463",
      ecNumber = "169456B",
      aldSheets = Vector("AA131", "AA132"),
      ildSheets = Vector("0303", "0309"),
      category = CardCategory.ClockAndTiming,
      description = "240 kHz Master Oscillator, Single-Shots SSA (2.4 us) & SSB (2.4 us), and LOW / EQUAL / TAKEN Condition Latches.",
      isActive = _ => true,
      pins = Vector(
        CardPinSignal("K6D13", "SSA (SINGLE SHOT A)", _.ssa),
        CardPinSignal("K6B13", "SSB (SINGLE SHOT B)", _.ssb),
        CardPinSignal("K5B04", "+LOW LATCH", _.low),
        CardPinSignal("K5B08", "+EQUAL LATCH", _.eq),
        CardPinSignal("K6B06", "+TAKEN (TKN)", _.tkn, Some(CeTestProbe.Taken))
      )
    ),

    // =============================================================================================
    // COLUMN L: Translator Interface 1, Console Lights 2, and Keyboard Encoding
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "L1/2",
      col = "L",
      rowStart = 1,
      rowSpan = 2,
      title = "INTERFACE 1",
      cardNumber = "6467",
      ecNumber = "172415C",
      aldSheets = Vector("AA171", "AA172"),
      ildSheets = Vector("0311"),
      category = CardCategory.ConsoleAndIo,
      description = "Selectric Composer Translator Interface 1: driver amplifiers for Select Magnets 1..7 and Translator Cycle Clutch.",
      isActive = s => s.tsltrPr || s.tsltrCycCl || s.translatorSelectMagnetsMask != 0,
      pins = Vector(
        CardPinSignal("L1B02", "+TSLTR PRINT", _.tsltrPr),
        CardPinSignal("L1B06", "+TSLTR CYC CL", _.tsltrCycCl),
        CardPinSignal("L2B04", "+ORDINARY CHAR", _.ordChar),
        CardPinSignal("L2B08", "+FUNCTION CHAR", _.fn)
      )
    ),
    BackplaneCardSpec(
      slotId = "L3/4",
      col = "L",
      rowStart = 3,
      rowSpan = 2,
      title = "LITES 2",
      cardNumber = "6452",
      ecNumber = "169044A",
      aldSheets = Vector("AA023", "AA024"),
      ildSheets = Vector("0312"),
      category = CardCategory.ConsoleAndIo,
      description = "Console Indicator Lamp Multiplexer 2: drives Lamps 4..9 across Display 1..4, Clock/Test (Display 0), and Operator Lites.",
      isActive = s => s.lite4 || s.lite5 || s.lite6 || s.lite7 || s.lite8 || s.lite9,
      pins = Vector(
        CardPinSignal("L3B02", "LAMP 4 (Y9/O8/T2)", _.lite4),
        CardPinSignal("L3B05", "LAMP 5 (Y8/O7/T1)", _.lite5),
        CardPinSignal("L3B08", "LAMP 6 (Y7/O6/ID)", _.lite6),
        CardPinSignal("L4B02", "LAMP 7 (Y6/O5/IC)", _.lite7),
        CardPinSignal("L4B05", "LAMP 8 (Y5/O4/IB)", _.lite8),
        CardPinSignal("L4B08", "LAMP 9 (Y4/O3/IA)", _.lite9)
      )
    ),
    BackplaneCardSpec(
      slotId = "L5/6",
      col = "L",
      rowStart = 5,
      rowSpan = 2,
      title = "KEY ENCODE",
      cardNumber = "6455",
      ecNumber = "169454B",
      aldSheets = Vector("AA051", "AA052"),
      ildSheets = Vector("0308", "0312"),
      category = CardCategory.ConsoleAndIo,
      description = "12-Key Console Keyboard Encoder (Keys 0..9, R, START -> KB CODE A..D), Keyboard Entry Strobe, and Line Stop Latch.",
      isActive = s => s.kbCodeRdy || s.kbEntStrb || s.lnStpLth,
      pins = Vector(
        CardPinSignal("L5B02", "+KB CODE A", _.kbCodeA),
        CardPinSignal("L5B05", "+KB CODE B", _.kbCodeB),
        CardPinSignal("L5B08", "+KB CODE C", _.kbCodeC),
        CardPinSignal("L5B11", "+KB CODE D", _.kbCodeD),
        CardPinSignal("L6B04", "+KB ENT STRB", _.kbEntStrb),
        CardPinSignal("L6D09", "+LINE STOP LTH", _.lnStpLth)
      )
    ),

    // =============================================================================================
    // COLUMN M: Translator Interface 2, CE Console Control, and Optional Paper Tape Interface
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "M1/2",
      col = "M",
      rowStart = 1,
      rowSpan = 2,
      title = "INTERFACE 2",
      cardNumber = "6468",
      ecNumber = "169459B",
      aldSheets = Vector("AA181"),
      ildSheets = Vector("0311"),
      category = CardCategory.ConsoleAndIo,
      description = "Selectric Composer Translator Interface 2: escapement feedback contacts, carriage return / tab interlocks, and Ready line.",
      isActive = s => s.tabOrCrn || s.dummy || s.dmyFn,
      pins = Vector(
        CardPinSignal("M1B02", "+TAB OR CRN", _.tabOrCrn),
        CardPinSignal("M1B06", "+DUMMY", _.dummy),
        CardPinSignal("M2B04", "+DUMMY FN", _.dmyFn)
      )
    ),
    BackplaneCardSpec(
      slotId = "M3/4",
      col = "M",
      rowStart = 3,
      rowSpan = 2,
      title = "CONSOLE",
      cardNumber = "6457",
      ecNumber = "169049A",
      aldSheets = Vector("AA071", "AA072"),
      ildSheets = Vector("0303", "0312"),
      category = CardCategory.ConsoleAndIo,
      description = "Customer Engineer Console Control: STOP CLOCK 1 & 2 Latches (Bit/Word/P.S./Jump single-step) and Display 1..4 Selector Latches.",
      isActive = s => s.stpClk1 || s.stpClk2 || s.aid1 || s.aid2 || s.aid3 || s.dply3 || s.dply4,
      pins = Vector(
        CardPinSignal("M3B02", "+STOP CLOCK 1", _.stpClk1),
        CardPinSignal("M3B05", "+STOP CLOCK 2", _.stpClk2),
        CardPinSignal("M4B02", "+AID 1 (DISPLAY 1)", _.aid1),
        CardPinSignal("M4B05", "+AID 2 (DISPLAY 2)", _.aid2),
        CardPinSignal("M4B08", "+DISPLAY 3 (OP)", _.dply3),
        CardPinSignal("M4B11", "+DISPLAY 4 (BFR)", _.dply4)
      )
    ),
    BackplaneCardSpec(
      slotId = "M5/6",
      col = "M",
      rowStart = 5,
      rowSpan = 2,
      title = "PAPER TAPE INTERFACE",
      cardNumber = "6679",
      ecNumber = "169433A",
      aldSheets = Vector("AA231", "AA232"),
      ildSheets = Vector("0313"),
      category = CardCategory.OptionCard,
      description = "Optional Paper Tape Reader Interface (Model 6310/6320 PT Option, ILD 0313). Unpopulated on standard Magnetic Tape Composer units.",
      installed = false,
      isActive = _ => false,
      pins = Vector(
        CardPinSignal("M5B02", "PT OPTION (UNPOPULATED)", _ => false)
      )
    ),

    // =============================================================================================
    // COLUMN N: Right-Edge Yellow Flat Ribbon Cable Sockets (N1..N6)
    // =============================================================================================
    BackplaneCardSpec(
      slotId = "N1",
      col = "N",
      rowStart = 1,
      rowSpan = 1,
      title = "TSLTR CABLE",
      cardNumber = "CABLE",
      ecNumber = "1202495",
      aldSheets = Vector("BC031"),
      ildSheets = Vector("0311"),
      category = CardCategory.RibbonCable,
      description = "Yellow flat ribbon cable from N1 to the IBM Selectric Composer Translator magnet bank and feedback contacts.",
      isActive = s => s.tsltrSel || s.tsltrPr,
      pins = Vector(
        CardPinSignal("N1B08", "TSLTR SELECT MAGNETS", s => s.translatorSelectMagnetsMask != 0),
        CardPinSignal("N1B10", "TSLTR CYCLE CLUTCH", _.tsltrCycCl)
      )
    ),
    BackplaneCardSpec(
      slotId = "N2",
      col = "N",
      rowStart = 2,
      rowSpan = 1,
      title = "MT CABLE 1",
      cardNumber = "CABLE",
      ecNumber = "1202496",
      aldSheets = Vector("BC041", "BC051", "BC061"),
      ildSheets = Vector("0301", "0302"),
      category = CardCategory.RibbonCable,
      description = "Yellow flat ribbon cable from N2 to the Model 6310 Magnetic Tape Reader read head, emitter contacts, and 8 pushbuttons.",
      isActive = s => s.mtRd || s.mtSch || s.charRdy,
      pins = Vector(
        CardPinSignal("N2B03", "MT TRACKS 1..9", s => s.mtDataLatchesMask != 0),
        CardPinSignal("N2B08", "MT EMITTER MPS A/B", s => s.mpsA || s.mpsB)
      )
    ),
    BackplaneCardSpec(
      slotId = "N3",
      col = "N",
      rowStart = 3,
      rowSpan = 1,
      title = "MT CABLE 2",
      cardNumber = "CABLE",
      ecNumber = "1202497",
      aldSheets = Vector("AA201", "AA202"),
      ildSheets = Vector("0301"),
      category = CardCategory.RibbonCable,
      description = "Yellow flat ribbon cable from N3 to the Model 6310 Magnetic Tape Reader solenoids, stepping magnets, and error lamp.",
      isActive = s => s.mtStepMags || s.mtCycClutch || s.errorLite,
      pins = Vector(
        CardPinSignal("N3D02", "MT STEPPING MAGNETS", _.mtStepMags),
        CardPinSignal("N3D09", "MT ERROR LAMP", _.errorLite)
      )
    ),
    BackplaneCardSpec(
      slotId = "N4",
      col = "N",
      rowStart = 4,
      rowSpan = 1,
      title = "CTRL PANEL CABLE 1",
      cardNumber = "CABLE",
      ecNumber = "1202493",
      aldSheets = Vector("BC011", "BC021"),
      ildSheets = Vector("0303", "0312"),
      category = CardCategory.RibbonCable,
      description = "Yellow flat ribbon cable from N4 to the Operator / Customer Engineer Control Panel switches and 12-key keypad.",
      isActive = s => s.kbCodeRdy || s.edit || !s.autoOrStep,
      pins = Vector(
        CardPinSignal("N4B02", "CONSOLE KEYPAD BUS", _.kbCodeRdy),
        CardPinSignal("N4D04", "CE CLOCK / EDIT SWITCHES", s => s.edit || !s.autoOrStep)
      )
    ),
    BackplaneCardSpec(
      slotId = "N5",
      col = "N",
      rowStart = 5,
      rowSpan = 1,
      title = "CTRL PANEL CABLE 2",
      cardNumber = "CABLE",
      ecNumber = "1202494",
      aldSheets = Vector("AA021", "AA022", "AA023", "AA024"),
      ildSheets = Vector("0312"),
      category = CardCategory.RibbonCable,
      description = "Yellow flat ribbon cable from N5 driving the 11 incandescent indicator lamps on the Control Panel.",
      isActive = s => s.lampsLeftToRight.exists(identity),
      pins = Vector(
        CardPinSignal("N5B07", "CONSOLE LAMPS 1..11", s => s.lampsLeftToRight.exists(identity))
      )
    ),
    BackplaneCardSpec(
      slotId = "N6",
      col = "N",
      rowStart = 6,
      rowSpan = 1,
      title = "PT CABLE",
      cardNumber = "CABLE",
      ecNumber = "1202433",
      aldSheets = Vector("CC011"),
      ildSheets = Vector("0313"),
      category = CardCategory.OptionCard,
      description = "Unpopulated Paper Tape Reader cable socket (N6).",
      installed = false,
      isActive = _ => false,
      pins = Vector(
        CardPinSignal("N6B02", "PT CABLE (UNCONNECTED)", _ => false)
      )
    )
  )

  private val bySlotMap: Map[String, BackplaneCardSpec] =
    AllCards.map(c => c.slotId -> c).toMap

  def findBySlot(slotId: String): Option[BackplaneCardSpec] =
    bySlotMap.get(slotId)

  /**
   * Look up the card or cable occupying `(col, row)` in `BOARD B1`, returning `(spec, isTopRowOfCard)`.
   */
  def cardAt(col: String, row: Int): Option[(BackplaneCardSpec, Boolean)] =
    AllCards.find(c => c.col == col && row >= c.rowStart && row < c.rowStart + c.rowSpan).map { c =>
      (c, row == c.rowStart)
    }
}
