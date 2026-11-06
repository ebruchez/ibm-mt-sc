package mtsc.cpu.ild

/**
 * Hardware oscilloscope channel definitions matching the **1967 IBM MT/SC 6310/6320 Maintenance
 * Diagram Manual Timing Diagrams (`Page 0601: Basic Clock and Timing Logic` and `Page 0602: Memory Logic`)**,
 * including authentic B1 backplane test pin designations.
 */
enum IldScopeChannel(
  val id: String,
  val label: String,
  val pin: String,
  val bitIndex: Int
) {
  case Ssa          extends IldScopeChannel("ssa",        "SSA",              "K6D13", 0)
  case Ssb          extends IldScopeChannel("ssb",        "SSB",              "K6B13", 1)
  case LatchA       extends IldScopeChannel("a",          "+A",               "G5D05", 2)
  case LatchB       extends IldScopeChannel("b",          "+B",               "G5B08", 3)
  case Twr          extends IldScopeChannel("twr",        "+TWR",             "G5B03", 4)
  case PTime        extends IldScopeChannel("p_time",     "+P TIME",          "G6B11", 5)
  case CenBt        extends IldScopeChannel("cen_bt",     "+CEN BIT T",       "G5D11", 6)
  case NotBAndTwr   extends IldScopeChannel("not_b_twr",  "-B AND -TWR",      "G5D10", 7)
  case BmpStrbT     extends IldScopeChannel("bmp_strb",   "+BMP STRB T",      "G6B02", 8)
  case CsXLog       extends IldScopeChannel("cs_x",       "CURR SRC X LOG",   "G6D02", 9)
  case CsYLog       extends IldScopeChannel("cs_y",       "CURR SRC Y LOG",   "G6D05", 10)
  case Rewo         extends IldScopeChannel("rewo",       "+REWO",            "D3D09", 11)
  case Rowe         extends IldScopeChannel("rowe",       "+ROWE",            "D3D05", 12)
  case PrmtTime     extends IldScopeChannel("prmt_time",  "+PRMT TIME",       "G6B10", 13)
  case StrLthStrb   extends IldScopeChannel("str_strb",   "+STR LTH STRB",    "G6B13", 14)
  case ResInd       extends IldScopeChannel("res_ind",    "+RESET IND",       "F6D06", 15)
  case LoadInd      extends IldScopeChannel("ld_ind",     "+LOAD IND",        "G5B11", 16)
  case ResBfr       extends IldScopeChannel("res_bfr",    "+RESET BFR",       "G6B04", 17)
  case POnly        extends IldScopeChannel("p_only",     "+P ONLY",          "F5D04", 18)
  case SelPsar      extends IldScopeChannel("sel_psar",   "+SEL PROG STEP",   "E3B12", 19)
  case SelAWd       extends IldScopeChannel("sel_a_wd",   "+SEL A WD",        "E3B11", 20)
  case SelBWd       extends IldScopeChannel("sel_b_wd",   "+SEL B WD",        "E3B07", 21)
  case SelMemSpec   extends IldScopeChannel("sel_spec",   "+SEL MEM SPEC",    "C6B12", 22)
  case LoadY        extends IldScopeChannel("load_y",     "+LOAD Y",          "H5B09", 23)
  case WrPBfr       extends IldScopeChannel("wr_p_bfr",   "+WR P BFR",        "H3D13", 24)
  case WrAccum      extends IldScopeChannel("wr_accum",   "+WR ACCUM",        "H3D07", 25)
  case InhRead      extends IldScopeChannel("inh_read",   "+INH READ",        "H5D02", 26)
  case CarOver      extends IldScopeChannel("car_over",   "+CARRY OVER",      "H5D09", 27)
}

object IldScopeChannel {
  /** Preset 1: **MDM Page `0601` (`TIMING DIAGRAM BASIC CLOCK AND TIMING LOGIC`)**. */
  val Preset0601BasicClock: Vector[IldScopeChannel] = Vector(
    Ssa, Ssb, LatchA, LatchB, Twr, PTime, CenBt, NotBAndTwr, BmpStrbT, CsXLog, CsYLog, Rewo, StrLthStrb, ResInd, LoadInd
  )

  /** Preset 2: **MDM Page `0602` (`TIMING DIAGRAM MEMORY LOGIC`)**. */
  val Preset0602MemoryLogic: Vector[IldScopeChannel] = Vector(
    Ssa, Ssb, POnly, SelPsar, SelAWd, SelBWd, SelMemSpec, CsXLog, CsYLog, LoadY, WrPBfr, WrAccum, ResBfr, InhRead, CarOver
  )

  /** Preset 3: **Clock Reference + Custom CE Test Probes (`Test 4..1`)**. */
  val PresetClockReference: Vector[IldScopeChannel] = Vector(
    Ssa, Ssb, LatchA, LatchB, Twr, PTime, CenBt, CsXLog, StrLthStrb
  )
}

/**
 * Single sub-phase snapshot (`2.083 us` half-pulse resolution at `240 kHz`) captured during
 * [[IldCcu.stepBitTime]].
 *
 * @param sampleIndex    Monotonic half-pulse sample index (`1 sample = 2.083 us`)
 * @param insnTime       Instruction Time (`0..9` for `I-0..I-9`)
 * @param bitTimeIndex   Bit Time index (`0..3` for `B1-4`, `B5-8`, `B9-12`, `B13-16`)
 * @param pulseInBitTime Single-shot pulse index within the current Bit Time (`1..8` for `P-Only`, `1..12` for `P & Q`)
 * @param subPhase       Oscillator single-shot sub-phase (`SsaOn`, `SsaOff`, `SsbOn`, `SsbOff`)
 * @param coreOp         Active 3D core array operation during this sub-phase (`""`, `"R-P"`, `"R-Q"`, `"W-Q"`, `"W-P"`)
 * @param signalsMask    Packed 28-bit mask of [[IldScopeChannel]] signals (`bit i = channel i`)
 * @param nibbles        Packed 12-bit nibbles (`bits 3..0 = P BFR`, `bits 7..4 = Q BFR`, `bits 11..8 = ACC`)
 * @param probeMask      4-bit mask of custom `CE Test 1..4` probe values or additional backplane signals
 */
final case class IldTimingSample(
  sampleIndex: Long,
  insnTime: Int,
  bitTimeIndex: Int,
  pulseInBitTime: Int,
  subPhase: OscSubPhase,
  coreOp: String,
  signalsMask: Int,
  nibbles: Int,
  probeMask: Int
) {
  def channel(ch: IldScopeChannel): Boolean =
    ((signalsMask >> ch.bitIndex) & 1) != 0

  def pBufferNibble: Int = nibbles & 0xf
  def qBufferNibble: Int = (nibbles >> 4) & 0xf
  def accNibble: Int     = (nibbles >> 8) & 0xf

  def bitTimeLabel: String = bitTimeIndex match {
    case 0 => "B1-4"
    case 1 => "B5-8"
    case 2 => "B9-12"
    case _ => "B13-16"
  }
}

/**
 * Fixed-capacity circular buffer recording sub-bit-time (`240 kHz` `SSA`/`SSB`) waveform samples
 * from [[IldCcu]].
 *
 * Capacity `192` samples (`96` single-shot pulses = `400 us`) holds up to **2 complete `P & Q`
 * Word Times (8 Bit Times)** or **3 complete `P-Only` Word Times (12 Bit Times)**, matching the
 * horizontal span of **MDM Pages `0601` and `0602`**.
 */
final class IldTimingTraceBuffer(val capacity: Int = 192) {
  private val buffer: Array[IldTimingSample] = new Array[IldTimingSample](capacity)
  private var head: Int = 0
  private var count: Int = 0
  private var nextSampleIndex: Long = 0L

  var enabled: Boolean = true
  var isPreview: Boolean = false

  def clear(): Unit = {
    head = 0
    count = 0
    nextSampleIndex = 0L
    isPreview = false
  }

  def seedPreview(samples: Seq[IldTimingSample]): Unit = {
    clear()
    for (sample <- samples.take(capacity)) {
      buffer(head) = sample
      head = (head + 1) % capacity
      if (count < capacity) count += 1
      nextSampleIndex += 1
    }
    isPreview = count > 0
  }

  def size: Int = count

  def totalSamplesRecorded: Long = nextSampleIndex

  /**
   * Captures the current [[BackplaneSignals]] state as one sub-phase sample in the ring buffer.
   */
  def record(
    s: BackplaneSignals,
    insnTime: Int,
    bitTimeIndex: Int,
    pulseInBitTime: Int,
    subPhase: OscSubPhase,
    coreOp: String = "",
    probeEvaluator: BackplaneSignals => Int = _ => 0
  ): Unit = {
    if (!enabled) return
    if (isPreview) {
      clear()
    }

    val csX = s.csYLog || s.csXLog
    val csY = s.csYLog
    val mask =
      (if (s.ssa) 1 << 0 else 0) |
      (if (s.ssb) 1 << 1 else 0) |
      (if (s.a) 1 << 2 else 0) |
      (if (s.b) 1 << 3 else 0) |
      (if (s.twr) 1 << 4 else 0) |
      (if (s.pTime) 1 << 5 else 0) |
      (if (s.cenBt) 1 << 6 else 0) |
      (if (!s.b && !s.twr) 1 << 7 else 0) |
      (if (s.bmpStrbT) 1 << 8 else 0) |
      (if (csX) 1 << 9 else 0) |
      (if (csY) 1 << 10 else 0) |
      (if (s.rewo) 1 << 11 else 0) |
      (if (s.rowe) 1 << 12 else 0) |
      (if (s.prmtTime) 1 << 13 else 0) |
      (if (s.strLthStrobe) 1 << 14 else 0) |
      (if (s.resInds) 1 << 15 else 0) |
      (if (s.ldInds) 1 << 16 else 0) |
      (if (s.resBfr) 1 << 17 else 0) |
      (if (s.pOnly) 1 << 18 else 0) |
      (if (s.selProgStepAddr) 1 << 19 else 0) |
      (if (s.selAWd) 1 << 20 else 0) |
      (if (s.selBWd) 1 << 21 else 0) |
      (if (s.selMemSpec) 1 << 22 else 0) |
      (if (s.ldY || s.ldYInsnT6) 1 << 23 else 0) |
      (if (s.wrPBfr) 1 << 24 else 0) |
      (if (s.wrAcc) 1 << 25 else 0) |
      (if (s.inhRd) 1 << 26 else 0) |
      (if (s.carOver) 1 << 27 else 0)

    val nibs =
      (s.pBufferNibble & 0xf) |
      ((s.qBufferNibble & 0xf) << 4) |
      ((s.accNibble & 0xf) << 8)

    val pMask = probeEvaluator(s) & 0xf

    val sample = IldTimingSample(
      sampleIndex = nextSampleIndex,
      insnTime = insnTime,
      bitTimeIndex = bitTimeIndex,
      pulseInBitTime = pulseInBitTime,
      subPhase = subPhase,
      coreOp = coreOp,
      signalsMask = mask,
      nibbles = nibs,
      probeMask = pMask
    )

    buffer(head) = sample
    head = (head + 1) % capacity
    if (count < capacity) count += 1
    nextSampleIndex += 1
  }

  /**
   * Returns the most recent `maxCount` samples in chronological order (oldest -> newest).
   */
  def recentSamples(maxCount: Int = capacity): Vector[IldTimingSample] = {
    val n = math.min(count, math.max(0, maxCount))
    if (n == 0) return Vector.empty
    val start = (head - n + capacity) % capacity
    val builder = Vector.newBuilder[IldTimingSample]
    builder.sizeHint(n)
    var i = 0
    while (i < n) {
      builder += buffer((start + i) % capacity)
      i += 1
    }
    builder.result()
  }
}
