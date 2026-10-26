# IBM MT/SC CCU Assembly Language Reference

**Composer Control Unit (Model 6320 / 6310)**  
*Instruction Set Architecture, Assembly Syntax, Mnemonics & Programming Guide*

---

## Table of Contents

1. [Architectural Overview](#1-architectural-overview)
   - [Mapping IBM Hardware Terminology to Assembly Syntax](#mapping-ibm-hardware-terminology-to-assembly-syntax)
   - [Memory Architecture & Storage Map](#memory-architecture--storage-map)
   - [Working Registers (R0–R31)](#working-registers-r0r31)
   - [Hardware Registers & Status Latches](#hardware-registers--status-latches)
2. [Assembly Language Syntax](#2-assembly-language-syntax)
   - [Statement Format & Comments](#statement-format--comments)
   - [Labels & Literals](#labels--literals)
   - [Assembler Directives](#assembler-directives)
3. [Addressing Modes & Register Constraints](#3-addressing-modes--register-constraints)
   - [Addressing Modes](#addressing-modes)
   - [Register Subsets by Instruction Group](#register-subsets-by-instruction-group)
   - [Peripheral Device Designators](#peripheral-device-designators)
4. [Condition Flags & Status Latches](#4-condition-flags--status-latches)
   - [Equal (EQ) and Low (LO) Latches](#equal-eq-and-low-lo-latches)
   - [Condition Tests](#condition-tests)
5. [Complete Instruction Set & Mnemonics](#5-complete-instruction-set--mnemonics)
   - [5.1 Full Branches (Jump & Link)](#51-full-branches-jump--link)
   - [5.2 Incremental Branches (Relative Bit-Testing)](#52-incremental-branches-relative-bit-testing)
   - [5.3 Program Control (Bit Manipulation)](#53-program-control-bit-manipulation)
   - [5.4 Immediate Arithmetic (SCAT)](#54-immediate-arithmetic-scat)
   - [5.5 Register Direct Arithmetic (SCAT)](#55-register-direct-arithmetic-scat)
   - [5.6 Byte Indirect Memory Operations](#56-byte-indirect-memory-operations)
   - [5.7 Peripheral Input / Output](#57-peripheral-input--output)
6. [Instruction Binary Encodings](#6-instruction-binary-encodings)
7. [Comprehensive Annotated Examples](#7-comprehensive-annotated-examples)
   - [Example 1: Authentic 3-Step Program Bootstrap Loader](#example-1-authentic-3-step-program-bootstrap-loader)
   - [Example 2: Subroutine Call & Return via Self-Modifying Linkage (`JMPL`)](#example-2-subroutine-call--return-via-self-modifying-linkage-jmpl)
   - [Example 3: Proportional Font Escapement Table Lookup](#example-3-proportional-font-escapement-table-lookup)
   - [Example 4: 16-Bit Software Integer Division Subroutine](#example-4-16-bit-software-integer-division-subroutine)
   - [Example 5: Complete Galley Line Justification Algorithm](#example-5-complete-galley-line-justification-algorithm)
   - [Example 6: Background Multiplex Typesetting to Selectric Composer](#example-6-background-multiplex-typesetting-to-selectric-composer)
8. [Quick-Reference Mnemonic Summary](#8-quick-reference-mnemonic-summary)

---

## 1. Architectural Overview

The **Composer Control Unit (CCU)** (IBM Model 6320 for dual magnetic tape stations, Model 6310 for paper tape) is the specialized 16-bit processor controlling the IBM Magnetic Tape "Selectric" Composer (MT/SC) cold-type composition system.

The CCU operates natively on **16-bit words** stored in **2,048 bytes of coincident-current magnetic core storage** (1,024 words). All instructions are 16 bits (2 bytes) in length and must reside at word-aligned (even) byte addresses.

> [!NOTE]
> **Historical Note on Assembly Syntax:**
> As documented in the *IBM Journal of Research and Development* (Vol. 12, No. 5, September 1968, p. 385):
> > *"Since the basic application called for only one program and all programming was to be performed in machine language by engineers in the logic design group, no programmer aids were built into the machine."*
> 
> IBM never released an assembler or formal textual assembly mnemonics for the MT/SC CCU; IBM logic engineers wrote the machine microprogram directly in machine language. The symbolic mnemonics (`JMP`, `JMPL`, `MOVI`, `ADDI`, `MOVB [Rn]++`, `OUTM`, etc.) and directives (`.org`, `.word`, etc.) used in this reference were developed for this emulator project, faithfully formalizing the exact hardware instruction names, bit patterns, and addressing modes documented in **Figure 10 ("Program Step Set")** of IBM CE Manual 241-5429-0.

### Mapping IBM Hardware Terminology to Assembly Syntax

The table below maps the historical IBM hardware terminology from Figure 10 to the symbolic assembly syntax defined for this emulator:

| IBM Hardware Term (Figure 10 / CE Manual) | Project Assembly Mnemonic / Syntax | Meaning |
|---|---|---|
| **SCAT** (Subtract, Compare, Add, Transfer) | `SUB`, `CMP`, `ADD`, `MOV` | 16-bit register arithmetic |
| Immediate SCAT | `SUBI`, `CMPI`, `ADDI`, `MOVI` | 8-bit literal arithmetic with even registers |
| Byte Transfer / Byte Compare | `MOVB`, `CMPB` | Memory byte operations |
| Indirect addressing with "Bump by 1" | `[Rn]++` | Pointer auto-increment on access |
| Unconditional Jump & Link | `JMPL` | Subroutine call (overwrites return slot) |
| Conditional Branch | `JMP.EQ`, `JMP.NE`, `JMP.LO`, `JMP.HI` | Branches based on condition latches |
| Incremental Jump on Bit | `IJMP.ON`, `IJMP.OFF` | Relative word jump testing register bit |
| Program Control | `TBIT`, `SBIT`, `RBIT`, `PBIT` | Bit test, set, reset, preserve |
| Output Translator (Multiplex) | `OUTM TSLTR, [Rn]` | Background cycle-stealing print mode |
| Location counter / data definition | `.org`, `.word`, `.byte`, `.space` | Standard assembler directives |

### Memory Architecture & Storage Map

Core storage spans byte addresses `0` through `2047` (`0x000` to `0x7FF`).

| Byte Address Range | Hex Range | Word Count | Standard Usage / Function |
|---|---|---|---|
| `0` .. `63` | `0x000` .. `0x03F` | 32 words | **Working Registers R0–R31** (2 bytes per register; `byteAddress = RegId * 2`) |
| `64` .. `197` | `0x040` .. `0x0C5` | 67 words | **Proportional Escapement Table** (134 character width values, 3–9 units each) |
| `198` .. `315` | `0x0C6` .. `0x13B` | 59 words | **Output Block Buffer** (holds formatted lines for background multiplex printing) |
| `316` .. `545` | `0x13C` .. `0x221` | 115 words | **Input Block Buffer** (holds raw text stream read from tape) |
| **`546`** | **`0x222`** | — | **Hardware Power-On Entry Point (`Address.PowerOn`)** |
| `546` .. `2047` | `0x222` .. `0x7FF` | 751 words | **Program Code & Subroutines Storage** |

> **Key Invariant:** The hardware power-on sequence forces the Program Step Address Register (`PSAR`) directly to byte **`546`** (`0x222`), avoiding operator intervention during boot.

### Working Registers (R0–R31)

The CCU provides **32 general-purpose 16-bit working registers**, designated `R0` through `R31`. Working registers physically reside in the lowest 64 bytes of core memory:

$$\text{Core Byte Address} = \text{RegId} \times 2$$

- `R0` (`0x000`–`0x001`): General register, often used as primary pointer for background **Multiplex output** interrupts.
- `R1` (`0x002`–`0x003`): General register / scratch.
- `R2` .. `R15` (`0x004` .. `0x01F`): Working registers capable of **Byte Indirect addressing** (`[Rn]` and `[Rn]++`).
- `R16` .. `R31` (`0x020` .. `0x03F`): High working registers for register-direct arithmetic, bit manipulation, and branching.
- `R20` (`0x028`–`0x029`): In the 1967 CE maintenance manual, holds the pointer for the 3-step tape bootstrap loader entered via the front panel keying table.

### Hardware Registers & Status Latches

- **PSAR (Program Step Address Register)**: 11-bit register (`0`–`2046`, even) holding the address of the current instruction word. Automatically advances by `+2` at I-0 time.
- **MAL (Memory Address Latch)**: 11-bit latch holding the storage address accessed during memory read/write cycles.
- **A-Word**: 16-bit data register holding the current instruction during execution.
- **B-Word**: 16-bit operand register holding data loaded from storage or the Q operand. *Hardware invariant: the B-Word is cleared to zero at the end of every instruction cycle.*
- **Equal (EQ)**: Single-bit condition latch. Set when arithmetic result is `0` or tested bit is `1`.
- **Low (LO)**: Single-bit condition latch. Set when arithmetic subtraction results in negative value (bit 15 set) or tested bit is `0`.
- **Inhibit**: Pipeline stall latch. When an I/O device is busy (`IoResult.Busy`), `Inhibit` freezes execution, restoring `PSAR` to the current instruction so it automatically retries on the next cycle.
- **Multiplex**: Background printing status latch. Enabled by `OUTM`; permits the Selectric Composer printer to read characters asynchronously while CCU continues executing code.

---

## 2. Assembly Language Syntax

The MT/SC assembly language follows a clean columnar or free-format syntax. Lines are case-insensitive for mnemonics and registers.

### Statement Format & Comments

Each line of source code adheres to:

```assembly
[LABEL:]   [MNEMONIC]   [OPERANDS]   [; Comments]
```

- **Comments**: Begin with a semicolon (`;`) and continue to the end of the line. Blank lines and lines containing only comments are ignored.
- **Labels**: An identifier ending with a colon (`:`). Labels may appear on their own line or immediately preceding an instruction or directive:
  ```assembly
  START:
  LOAD_LOOP:  IN      TAPE_R, R4
  ```
- **Operands**: Comma-delimited list of registers, addresses, immediate values, or memory brackets.

### Labels & Literals

- **Symbols / Labels**: Represent 11-bit core addresses. Must be unique across the source program.
- **Numeric Literals**:
  - Decimal: `546`, `150`, `64`
  - Hexadecimal (prefixed with `0x` or `0X`): `0x222`, `0x02A`, `0xFF`
- **Registers**: `R0`, `R1`, ..., `R31` (case-insensitive).

### Assembler Directives

The assembler supports four directives:

| Directive | Operand | Example | Description |
|---|---|---|---|
| `.ORG` | `<address>` | `.org 546`<br>`.org 0x222` | Sets the current program counter (PC) for subsequent statements. Default initial PC is `546` (`0x222`). |
| `.WORD` | `<expr>` | `.word 0x0000`<br>`.word TARGET` | Emits a 16-bit word at the current PC. `<expr>` may be a decimal number, hex literal, or label symbol. Advances PC by 2. |
| `.BYTE` | `<value>` | `.byte 0x55`<br>`.byte 13` | Emits an 8-bit byte (`0`–`255`). Advances PC by 1. |
| `.SPACE` | `<count>` | `.space 16` | Emits `<count>` zero bytes (`0x00`) in sequence. Advances PC by `<count>`. |

---

## 3. Addressing Modes & Register Constraints

The CCU architecture provides four primary addressing modes, plus peripheral device addressing:

### Addressing Modes

1. **Register Direct (`Rn`)**:
   Operates directly on the 16-bit value of working register `Rn`.
   ```assembly
   MOV     R12, R30         ; Copy 16-bit word from R30 to R12
   ADD     R2, R4           ; R2 = R2 + R4
   ```

2. **Immediate (`imm8`)**:
   Specifies an unsigned 8-bit constant literal (`0` to `255`, or `0x00` to `0xFF`).
   ```assembly
   MOVI    R8, 60           ; R8 = 60 (high byte cleared to 0)
   ADDI    R4, 64           ; R4 = R4 + 64
   CMPI    R22, 0           ; Compare R22 with 0
   ```

3. **Byte Indirect (`[Rn]`)**:
   Accesses the single 8-bit memory byte at the address contained in register `Rn`.
   ```assembly
   MOVB    R10, [R4]        ; Load byte at memory address R4 into low byte of R10
   MOVB    [R8], R4         ; Store low byte of R4 into memory byte at address R8
   ```

4. **Byte Indirect with Auto-Increment (`[Rn]++`)**:
   Accesses the memory byte at address `Rn`, and **automatically increments the pointer register `Rn` by +1** during execution.
   ```assembly
   MOVB    [R8]++, R4       ; Store low byte of R4 at [R8], then increment R8 by 1
   MOVB    R4, [R8]++       ; Load byte from [R8] into R4, then increment R8 by 1
   ```

### Register Subsets by Instruction Group

Hardware bit limitations in the 16-bit instruction word dictate register constraints:

| Instruction Group | Valid Register Set | Hardware Reason |
|---|---|---|
| **Register Direct Arithmetic** (`MOV`, `ADD`, `SUB`, `CMP`) | `R0` – `R31` (all 32) | 5-bit register fields for both P and Q |
| **Bit Manipulation** (`TBIT`, `SBIT`, `RBIT`, `PBIT`) | `R0` – `R31` (all 32) | 5-bit register field |
| **Immediate Arithmetic** (`MOVI`, `ADDI`, `SUBI`, `CMPI`) | **Even registers only** (`R0`, `R2`, `R4`, ..., `R30`) | Encoded as `reg / 2` in a 4-bit field |
| **Byte Indirect Arithmetic** (`MOVB`, `CMPB`) | **`R0` – `R15` only** | 4-bit register index field |
| **Incremental Branch** (`IJMP.ON`, `IJMP.OFF`) | **`R0` – `R3` only** | 2-bit register field |
| **Peripheral Input** (`IN`) | `R0` – `R31` (all 32) | 5-bit register field |
| **Peripheral Output** (`OUT`, `OUTM`) | `R0` – `R31` (all 32) | 5-bit register field |

### Peripheral Device Designators

The CCU connects to peripherals via dedicated device codes. The assembler recognizes the following device names:

| Device Name | Aliases | Description |
|---|---|---|
| `TAPE_R` | `TAPE`, `RIGHT_TAPE` | Right Magnetic Tape Transport (Station 1) |
| `TAPE_L` | `LEFT_TAPE` | Left Magnetic Tape Transport (Station 2 / Merge station) |
| `PAPER` | `PAPER_TAPE` | Paper Tape Reader (IBM Model 6310) |
| `KB` | `KEYBOARD` | Operator Keyboard / CE Console |
| `TSLTR` | `TRANSLATOR`, `COMP` | Modified Selectric Composer printing mechanism |
| `LTS` | `LIGHTS` | Front CE Console Display Lamps |

---

## 4. Condition Flags & Status Latches

All branching decisions in the CCU rely on two hardware condition latches: **Equal (EQ)** and **Low (LO)**.

### Equal (EQ) and Low (LO) Latches

- **Arithmetic Operations** (`ADD`, `SUB`, `CMP`, `ADDI`, `SUBI`, `CMPI`):
  - `EQ` is set to `1` if the 16-bit result is zero (`result == 0`), else `0`.
  - `LO` is set to `1` if the 16-bit result has sign bit 15 set (`result & 0x8000 != 0`, indicating a negative two's complement value), else `0`.
- **Immediate Transfer** (`MOVI`):
  - `EQ = (imm == 0)`, `LO = 0`.
- **Register Transfer** (`MOV`):
  - `EQ = (qVal == 0)`, `LO = (qVal & 0x8000 != 0)`.
- **Byte Indirect Transfer** (`MOVB`):
  - `EQ = (byte == 0)`, `LO = 0`.
- **Byte Indirect Compare** (`CMPB`):
  - Compares `pByte - qByte`: `EQ = (diff == 0)`, `LO = (diff < 0)`.
- **Bit Operations** (`TBIT`, `SBIT`, `RBIT`, `PBIT`):
  - Samples the specified bit:
    - If bit is `1`: `EQ = 1`, `LO = 0`.
    - If bit is `0`: `EQ = 0`, `LO = 1`.

### Condition Tests

Branch instructions evaluate `EQ` and `LO` as follows:

| Condition Mnemonic | Meaning | Hardware Logic | Typical Usage |
|---|---|---|---|
| *(Unconditional)* | Always branch | Always `true` | Unconditional jumps, loops |
| `EQ` | Equal / Zero | `EQ == 1` | Branch after `CMP` equal or bit is `1` |
| `NE` | Not Equal / Non-zero | `EQ == 0` | Branch after `CMP` not equal or bit is `0` |
| `LO` | Low / Negative | `LO == 1` | Branch after subtraction underflow or $P < Q$ |
| `HI` | High / Greater than | `LO == 0 && EQ == 0` | Branch when $P > Q$ (positive and non-zero) |

---

## 5. Complete Instruction Set & Mnemonics

### 5.1 Full Branches (Jump & Link)

Full branches perform an absolute jump to an 11-bit word-aligned byte address (`0` to `2046`, even).

```assembly
MNEMONIC   <target>
```

| Mnemonic | Target Operand | Condition Tested | Description |
|---|---|---|---|
| `JMP` | `<target>` | None (Unconditional) | Jumps unconditionally: `PSAR = target`. |
| `JMP.EQ` | `<target>` | `EQ == 1` | Jump if Equal (Zero). |
| `JMP.NE` | `<target>` | `EQ == 0` | Jump if Not Equal (Non-zero). |
| `JMP.LO` | `<target>` | `LO == 1` | Jump if Low (Negative). |
| `JMP.HI` | `<target>` | `LO == 0 && EQ == 0` | Jump if High (Positive non-zero). |
| `JMPL` | `<target>` | None (Unconditional) | **Jump and Link (Subroutine Call)**. Writes an unconditional return jump (`JMP PC+2`) directly into the word at `<target>`, then jumps to `<target> + 2`. |

#### How `JMPL` Works (Subroutine Linkage)

Because the CCU has no hardware call stack, subroutines use **self-modifying return linkage**:

```assembly
; Calling Code:
    JMPL    MY_SUBROUTINE        ; 1. Writes 'JMP RETURN_ADDR' into word at MY_SUBROUTINE
RETURN_ADDR:                     ; 2. Jumps to MY_SUBROUTINE + 2
    ...

; Subroutine Definition:
.org 800
MY_SUBROUTINE:
    .word   0x0000               ; Return slot: dynamically overwritten by JMPL
    ; Subroutine body begins here (at address 802):
    ADD     R2, R4
    JMP     MY_SUBROUTINE        ; Returns to caller by jumping to the return slot!
```

---

### 5.2 Incremental Branches (Relative Bit-Testing)

Incremental branches test a single bit of a register and jump forward or backward by a relative number of words without altering condition latches.

```assembly
IJMP.ON    <reg>, <bit>, <target>
IJMP.OFF   <reg>, <bit>, <target>
```

- `<reg>`: Working register **`R0`, `R1`, `R2`, or `R3`** only (2-bit field).
- `<bit>`: Bit index **`0` to `15`**.
- `<target>`: Destination label or address.
  - Forward range: **`+1` to `+16` words** (`+2` to `+32` bytes relative to `PC + 2`).
  - Backward range: **`-1` to `-14` words** (`-2` to `-28` bytes relative to `PC + 2`).

| Mnemonic | Condition | Description |
|---|---|---|
| `IJMP.ON` | Bit == `1` | Relative branch if bit `<bit>` of `<reg>` is set (`1`). |
| `IJMP.OFF` | Bit == `0` | Relative branch if bit `<bit>` of `<reg>` is cleared (`0`). |

**Example:**
```assembly
    IJMP.ON   R0, 3, FORWARD_TARGET    ; Branch if bit 3 of R0 is 1
    ADDI      R2, 1
FORWARD_TARGET:
    IJMP.OFF  R1, 7, START             ; Branch backward to START if bit 7 of R1 is 0
```

---

### 5.3 Program Control (Bit Manipulation)

Bit control instructions test, set, clear, or preserve individual bits across any of the 32 working registers. The sampled bit always updates the `EQ` and `LO` condition latches (`bit=1` $\rightarrow$ `EQ=1, LO=0`; `bit=0` $\rightarrow$ `EQ=0, LO=1`).

```assembly
MNEMONIC   <reg>, <bit>
```

- `<reg>`: Any working register **`R0` through `R31`**.
- `<bit>`: Bit index **`0` to `15`**.

| Mnemonic | Operation | Latches Updated | Description |
|---|---|---|---|
| `TBIT` | Test Bit | `EQ=1, LO=0` if bit 1<br>`EQ=0, LO=1` if bit 0 | Samples bit `<bit>` in `<reg>`. Register contents unchanged. |
| `SBIT` | Set Bit | `EQ`/`LO` set from old bit value | Sets bit `<bit>` of `<reg>` to `1`. |
| `RBIT` | Reset Bit | `EQ`/`LO` set from old bit value | Clears bit `<bit>` of `<reg>` to `0`. |
| `PBIT` | Preserve Bit | `EQ`/`LO` set from bit value | Identical to `TBIT`; samples bit without modifying register. |

**Example:**
```assembly
    MOVI    R6, 0
    SBIT    R6, 8            ; Set bit 8 (adds 256): R6 = 256
    TBIT    R6, 8            ; Test bit 8
    JMP.EQ  BIT_WAS_SET      ; Jumps because bit 8 is ON (EQ=1)
```

---

### 5.4 Immediate Arithmetic (SCAT)

Performs SCAT (Subtract, Compare, Add, Transfer) operations between an unsigned 8-bit literal and an **even working register**.

```assembly
MNEMONIC   <reg>, <imm8>
```

- `<reg>`: **Even registers only** (`R0`, `R2`, `R4`, ..., `R30`).
- `<imm8>`: 8-bit constant (`0` to `255`, or `0x00` to `0xFF`).

| Mnemonic | Operation | High Byte (Bits 15..8) | Latches Updated | Description |
|---|---|---|---|---|
| `MOVI` | Transfer Immediate | **Cleared to `0`** | `EQ = (imm == 0)`, `LO = 0` | Loads 8-bit unsigned value into `<reg>`. |
| `ADDI` | Add Immediate | Participates in 16-bit add | 16-bit `EQ` and `LO` | `reg = (reg + imm) & 0xFFFF`. |
| `SUBI` | Subtract Immediate | Participates in 16-bit sub | 16-bit `EQ` and `LO` | `reg = (reg - imm) & 0xFFFF`. |
| `CMPI` | Compare Immediate | Preserved | 16-bit `EQ` and `LO` | Computes `(reg - imm) & 0xFFFF`. `<reg>` unchanged! |

**Example:**
```assembly
    MOVI    R8, 60           ; R8 = 60 (high byte = 0)
    SBIT    R8, 8            ; Set bit 8 (add 256) -> R8 = 316
    ADDI    R8, 10           ; R8 = 326
    CMPI    R8, 350          ; Compare 326 with 350
    JMP.LO  BELOW_LIMIT      ; Jumps because 326 < 350 (LO=1)
```

---

### 5.5 Register Direct Arithmetic (SCAT)

Performs 16-bit SCAT operations between two working registers. The result is stored in `<pReg>` (except for `CMP`, which preserves `<pReg>`). `<qReg>` is loaded into the hardware B-Word and is always preserved.

```assembly
MNEMONIC   <pReg>, <qReg>
```

- `<pReg>`: Destination register **`R0` through `R31`**.
- `<qReg>`: Source register **`R0` through `R31`**.

| Mnemonic | Operation | `<pReg>` Modified? | Latches Updated | Description |
|---|---|---|---|---|
| `MOV` | Transfer | Yes (`pReg = qReg`) | `EQ = (qVal == 0)`, `LO = sign` | Copies 16-bit word from `<qReg>` to `<pReg>`. |
| `ADD` | Add | Yes (`pReg = pReg + qReg`) | 16-bit `EQ` and `LO` | Adds 16-bit values. |
| `SUB` | Subtract | Yes (`pReg = pReg - qReg`) | 16-bit `EQ` and `LO` | Subtracts: `pReg = pReg - qReg`. |
| `CMP` | Compare | **No** (Preserved) | 16-bit `EQ` and `LO` | Compares `pReg - qReg` without altering either register. |

**Example:**
```assembly
    MOV     R12, R30         ; R12 = Target Measure (from R30)
    SUB     R12, R20         ; R12 = Deficit = Target (R12) - Accumulated (R20)
    JMP.LO  OVER_MEASURE     ; If negative (LO=1), line exceeded measure!
```

---

### 5.6 Byte Indirect Memory Operations

Transfers or compares single bytes in core memory using registers as pointers.

```assembly
MOVB    <dest>, <src>
CMPB    <dest>, <src>
```

- Registers must be in the range **`R0` through `R15`**.
- Memory operand format:
  - `[Rn]`: Byte at memory address held in `Rn`.
  - `[Rn]++`: Byte at memory address held in `Rn`, then **`Rn` is auto-incremented by +1**.
- At least one operand must be indirect (`[...]`).

| Mnemonic | Form | Description |
|---|---|---|
| `MOVB` | `[pReg]++, qReg` | Store low byte of `qReg` to memory at `[pReg]`, increment `pReg`. |
| `MOVB` | `[pReg], qReg` | Store low byte of `qReg` to memory at `[pReg]` (pointer unchanged). |
| `MOVB` | `pReg, [qReg]++` | Load byte at `[qReg]` into low byte of `pReg`, increment `qReg` (high byte preserved). |
| `MOVB` | `pReg, [qReg]` | Load byte at `[qReg]` into low byte of `pReg` (pointer unchanged). |
| `MOVB` | `[pReg]++, [qReg]++` | Memory-to-memory byte transfer with both pointers incremented. |
| `CMPB` | `[pReg], qReg` | Compare memory byte `[pReg]` with low byte of `qReg`. Neither modified. |
| `CMPB` | `pReg, [qReg]` | Compare low byte of `pReg` with memory byte `[qReg]`. Neither modified. |

**Condition Latches:**
- `MOVB`: Sets `EQ = (byte == 0)`, `LO = 0`.
- `CMPB`: Sets `EQ = (pByte == qByte)`, `LO = (pByte < qByte)`.

---

### 5.7 Peripheral Input / Output

Transfers single bytes between CCU registers/memory and peripheral devices.

```assembly
IN      <device>, <reg>
OUT     <device>, <src>
OUTM    <device>, [reg]
```

- **Operands Order**: The assembler also accepts `<reg>, <device>` for `IN`, and `<src>, <device>` for `OUT`/`OUTM`.
- **Inhibit Handling**: If a device is not ready (`IoResult.Busy`), the CCU automatically raises the `Inhibit` latch, cancels the PSAR increment, and stalls until the device completes.

| Mnemonic | Device | Source/Dest | Mode | Description |
|---|---|---|---|---|
| `IN` | `TAPE_R`, `TAPE_L`, `PAPER`, `KB` | `Rn` (low byte) | Direct | Reads character into `<reg>`. Clears high byte of `<reg>` to `0`. |
| `OUT` | `TSLTR`, `LTS` | `Rn` or `[Rn]` | Direct | Transmits byte to device. If indirect `[Rn]`, `Rn` is auto-incremented by `+1`. |
| `OUTM` | `TSLTR` | `[Rn]` | **Multiplex** | Initiates background asynchronous printing. CCU resumes code execution while printer cycles characters from memory. |

> **Multiplex Mode Halt:** Sending a byte with value `0xFF` terminates Multiplex printing mode.

---

## 6. Instruction Binary Encodings

The CCU Model 6320 uses a uniform 16-bit instruction word format, divided according to **Figure 10 of IBM CE Manual 241-5429-0**:

```text
Bits:  15  14  13  12  11  10   9   8   7   6   5   4   3   2   1   0
       --------------------------------------------------------------
```

### 1. Full Branch (`JMP`, `JMP.cond`, `JMPL`)
```text
Bits: [ 0   0 ] [ Op3..4 ] [ Link ] [       Target Address (bits 10..1)       ] [ Op7 ]
Width:    2          2         1                         10                        1
```
- `Op1..2` = `00`
- `Link`: `1` for `JMPL`, `0` for normal `JMP`
- `Target Address`: Word address (bits 10 down to 1)
- Condition Encoding:
  - `Unconditional`: `Op3..4 = 00`, `Op7 = 0`
  - `HI`: `Op3..4 = 01`, `Op7 = 1`
  - `LO`: `Op3..4 = 10`, `Op7 = 0`
  - `EQ`: `Op3..4 = 10`, `Op7 = 1`
  - `NE`: `Op3..4 = 11`, `Op7 = 0`

### 2. Incremental Branch (`IJMP.ON`, `IJMP.OFF`)
```text
Bits: [ 1   0 ] [ F ] [ 0 ] [ B ] [  Bit (0..15)  ] [ Reg (0..3) ] [ Steps-1 ] [ 0 ]
Width:    2       1     1     1          4                 2             4       1
```
- `Op1..2` = `10`
- `F`: `1` = Forward (+), `0` = Backward (-)
- `B`: `1` = Jump on bit set (`IJMP.ON`), `0` = Jump on bit clear (`IJMP.OFF`)
- `Bit`: 4 bits selecting bit 0–15
- `Reg`: 2 bits selecting R0, R1, R2, or R3
- `Steps-1`: 4 bits encoding 1 to 16 steps

### 3. Bit Manipulation / Program Control (`TBIT`, `SBIT`, `RBIT`, `PBIT`)
```text
Bits: [ 0   0   1   1 ] [ Op5 ] [  Bit (0..15)  ] [ Op6 ] [   Reg (0..31)   ] [ 1 ]
Width:        4            1            4            1             5            1
```
- `Op1..4` = `0011`, `Op7` = `1`
- `(Op5, Op6)`:
  - `(0, 0)`: `RBIT` (Reset to 0)
  - `(0, 1)`: `PBIT` (Preserve / Sample)
  - `(1, 0)`: `SBIT` (Set to 1)
  - `(1, 1)`: `TBIT` (Test)

### 4. Immediate Arithmetic (`MOVI`, `ADDI`, `SUBI`, `CMPI`)
```text
Bits: [ 1   1 ] [   Op   ] [ Reg / 2 (0..15) ] [      Immediate Literal (0..255)      ]
Width:    2         2                4                               8
```
- `Op1..2` = `11`
- `Op`: `00` = `SUBI`, `01` = `CMPI`, `10` = `ADDI`, `11` = `MOVI`
- `Reg / 2`: 4-bit index of even register (`R0` $\rightarrow 0$, `R2` $\rightarrow 1$, ..., `R30` $\rightarrow 15$)
- `Immediate`: 8-bit literal constant

### 5. Register Direct Arithmetic (`MOV`, `ADD`, `SUB`, `CMP`)
```text
Bits: [ 0   1 ] [   Op   ] [   P-Reg (0..31)   ] [ 0 ] [   Q-Reg (0..31)   ] [ 0 ]
Width:    2         2                5             1             5             1
```
- `Op1..2` = `01`, `Op6` = `0`, `Op7` = `0`
- `Op`: `00` = `SUB`, `01` = `CMP`, `10` = `ADD`, `11` = `MOV`

### 6. Byte Indirect Memory Operations (`MOVB`, `CMPB`)
```text
Bits: [ 0   1 ] [ Op ] [ Bump ] [ P-Reg (0..15) ] [ Q-Ind ] [ Q-Reg (0..15) ] [ P-Ind ]
Width:    2       1       1             5             1             5             1
```
- `Op1..2` = `01`
- `Op`: `0` = `CMPB`, `1` = `MOVB`
- `Bump`: `1` if auto-increment `++` specified, else `0`
- `P-Ind`: `1` if P operand is indirect memory `[pReg]`
- `Q-Ind`: `1` if Q operand is indirect memory `[qReg]`

### 7. Peripheral Input (`IN`)
```text
Bits: [ 0   0   0   1   1 ] [ Device (4b) ] [ 0 ] [   Reg (0..31)   ] [ 0 ]
Width:          5                   4         1            5            1
```
- `Op1..5` = `00011`
- `Device`: `0000` = `TAPE_R`, `0010` = `TAPE_L`, `0001` = `PAPER`, `0111` = `KB`

### 8. Peripheral Output (`OUT`, `OUTM`)
```text
Bits: [ 0   0   0   1   0 ] [ M ] [ Device (3b) ] [ Ind ] [   Reg (0..31)   ] [ 0 ]
Width:          5           1          3          1             5            1
```
- `Op1..5` = `00010`
- `M`: `1` = Multiplex background printing (`OUTM`), `0` = Direct output (`OUT`)
- `Device`: `000` = `TSLTR`, `111` = `LTS`
- `Ind`: `1` = Indirect memory `[reg]`, `0` = Register direct

---

## 7. Comprehensive Annotated Examples

### Example 1: Authentic 3-Step Program Bootstrap Loader

This is the historic 3-instruction loop entered manually by IBM Customer Engineers via the front-panel toggle buttons (or silk-screened `INITIAL LOADER` table) to cold-boot the machine from a program tape:

```assembly
; ==============================================================================
; Authentic IBM MT/SC 3-Step Program Bootstrap Loader
; Address 546 is the hardware Power-On entry vector.
; ==============================================================================
.org 546
BOOTSTRAP_LOADER:
    IN      TAPE_R, R4       ; 1. Read one 8-bit character from Right Tape into R4
    MOVB    [R8]++, R4       ; 2. Store byte into memory at [R8], increment pointer
    JMP     BOOTSTRAP_LOADER ; 3. Loop until entire 2,048-byte program is loaded
```

*(Note: During cold boot, `R8` starts initialized to `0x000` or the application buffer start).*

---

### Example 2: Subroutine Call & Return via Self-Modifying Linkage (`JMPL`)

Demonstrates how subroutines are structured, called, and returned from without a stack:

```assembly
.org 546
MAIN:
    MOVI    R0, 25           ; Initialize R0 = 25
    JMPL    MULTIPLY_BY_FOUR ; Call subroutine (writes return jump to 600)
    ; Returned: R0 is now 100
    CMPI    R0, 100
    JMP.EQ  SUCCESS
FAIL_HALT:
    JMP     FAIL_HALT

SUCCESS:
    MOVI    R30, 0xAA        ; Completion flag
DONE:
    JMP     DONE

; ------------------------------------------------------------------------------
; Subroutine: MULTIPLY_BY_FOUR
; Doubles R0 twice using register addition.
; ------------------------------------------------------------------------------
.org 600
MULTIPLY_BY_FOUR:
    .word   0x0000           ; Reserved slot! JMPL dynamically writes 'JMP MAIN+2'
    ADD     R0, R0           ; R0 = R0 * 2
    ADD     R0, R0           ; R0 = R0 * 2
    JMP     MULTIPLY_BY_FOUR ; Return: executes the return jump stored at 600
```

---

### Example 3: Proportional Font Escapement Table Lookup

Looks up font widths (in proportional units, 3–9) for incoming text and accumulates total line width:

```assembly
.org 546
START_MEASURE:
    ; Setup text pointer: R8 = 316 (Input Buffer: 256 + 60)
    MOVI    R8, 60
    SBIT    R8, 8            ; R8 = 316
    MOVI    R20, 0           ; R20 = Accumulated line measure (units)
    MOVI    R30, 150         ; R30 = Target line measure threshold

READ_LOOP:
    MOVB    R4, [R8]++       ; Read character into R4, advance pointer
    CMPI    R4, 0            ; Null terminator?
    JMP.EQ  LINE_COMPLETE

    ; Look up proportional escapement units:
    ; Table resides at core bytes 64..197, indexed by character code.
    ADDI    R4, 64           ; Table address = 64 + char_code
    MOVB    R10, [R4]        ; R10 = unit width from core storage table
    ADD     R20, R10         ; R20 += character width

    ; Check if line limit reached
    CMP     R30, R20         ; Compare Target (R30) - Accumulated (R20)
    JMP.LO  LINE_EXCEEDED    ; Jump if R20 > R30 (LO set)
    JMP     READ_LOOP

LINE_EXCEEDED:
    MOVI    R24, 1           ; Flag: line full
    JMP     HALT

LINE_COMPLETE:
    MOVI    R24, 0           ; Flag: end of text
HALT:
    JMP     HALT
```

---

### Example 4: 16-Bit Software Integer Division Subroutine

Because the CCU Model 6320 lacks hardware divide instructions, justification spacing calculations use repeated subtraction:

```assembly
; ==============================================================================
; Subroutine: DIVIDE_SUB
; Inputs:
;   R0 = Dividend
;   R2 = Divisor
; Outputs:
;   R4 = Quotient  (Dividend / Divisor)
;   R6 = Remainder (Dividend % Divisor)
; Clobbers:
;   R0, R4, R6
; ==============================================================================
.org 768
DIVIDE_SUB:
    .word   0x0000           ; Linkage slot overwritten by JMPL
    MOVI    R4, 0            ; Quotient = 0

DIV_LOOP:
    CMP     R0, R2           ; Compare Dividend - Divisor
    JMP.LO  DIV_DONE         ; If Dividend < Divisor, division is finished
    SUB     R0, R2           ; Dividend = Dividend - Divisor
    ADDI    R4, 1            ; Quotient += 1
    JMP     DIV_LOOP

DIV_DONE:
    MOV     R6, R0           ; Remainder = left-over Dividend
    JMP     DIVIDE_SUB       ; Return to caller via entry slot
```

---

### Example 5: Complete Galley Line Justification Algorithm

Calculates inter-word space expansion to produce camera-ready right-justified text, verifying that word spacing remains within typographic limits (3 to 9 units):

```assembly
; ==============================================================================
; Galley Line Justification Algorithm
; Inputs:
;   R30 = Target Line Measure (e.g., 150 units)
;   R20 = Accumulated Line Text Units (e.g., 138 units)
;   R22 = Inter-word Space Count (e.g., 4 spaces)
; Outputs:
;   R12 = Deficit (Target - Accumulated, e.g., 12 units)
;   R14 = Base Extra Units per space (Quotient, e.g., 3 units)
;   R16 = Remainder Units to distribute (Remainder, e.g., 0 units)
;   R18 = Success Flag (1 = valid space 3..9 units; 0 = justification failed)
; ==============================================================================
.org 546
JUSTIFY_CALC:
    ; Step 1: Calculate Deficit = Target (R30) - Accumulated (R20)
    MOV     R12, R30         ; R12 = Target Measure
    SUB     R12, R20         ; R12 = Deficit
    JMP.LO  JUSTIFY_FAIL     ; If negative, line already exceeds measure!

    ; Step 2: Ensure at least one space exists to justify
    CMPI    R22, 0
    JMP.EQ  JUSTIFY_FAIL     ; Cannot justify a line with 0 spaces

    ; Step 3: Call DIVIDE_SUB: Dividend in R0, Divisor in R2
    MOV     R0, R12          ; Dividend = Deficit
    MOV     R2, R22          ; Divisor = Space Count
    JMPL    DIVIDE_SUB       ; Returns: R4 = Quotient, R6 = Remainder

    MOV     R14, R4          ; R14 = Base extra units per space
    MOV     R16, R6          ; R16 = Remainder units to distribute

    ; Step 4: Typographic validation: Base Space (default 4 + Quotient) must be 3..9
    MOVI    R18, 4           ; Default space width = 4 units
    ADD     R18, R14         ; Total space width = 4 + Quotient
    CMPI    R18, 9           ; Compare TotalSpace - 9
    JMP.HI  JUSTIFY_FAIL     ; Exceeds maximum 9 units
    CMPI    R18, 3           ; Compare TotalSpace - 3
    JMP.LO  JUSTIFY_FAIL     ; Below minimum 3 units

    ; Justification Succeeded!
    MOVI    R18, 1
    JMP     JUSTIFY_DONE

JUSTIFY_FAIL:
    MOVI    R18, 0           ; Justification failed flag

JUSTIFY_DONE:
    JMP     JUSTIFY_DONE

; Include Division Subroutine at 768:
.org 768
DIVIDE_SUB:
    .word   0x0000
    MOVI    R4, 0
DIV_LOOP:
    CMP     R0, R2
    JMP.LO  DIV_FIN
    SUB     R0, R2
    ADDI    R4, 1
    JMP     DIV_LOOP
DIV_FIN:
    MOV     R6, R0
    JMP     DIVIDE_SUB
```

---

### Example 6: Background Multiplex Typesetting to Selectric Composer

Initiates background printing from the Output Block (`198`..`315`) to the Selectric Composer printer translator, allowing the CCU to process the next tape block in parallel:

```assembly
.org 546
PRINT_JUSTIFIED_LINE:
    ; Point R0 to the prepared Output Block (byte address 198)
    MOVI    R0, 198

    ; Start Multiplex printing!
    ; Translator reads byte at [R0], increments R0, and cycles the spherical typeball
    ; asynchronously in the background.
    OUTM    TSLTR, [R0]

    ; CCU continues immediately with next tasks while printing proceeds:
NEXT_TASK:
    ADDI    R26, 1           ; Track lines processed
    ; (When the translator encounters 0xFF in the buffer, Multiplex mode halts)
IDLE_LOOP:
    JMP     IDLE_LOOP
```

---

## 8. Quick-Reference Mnemonic Summary

| Mnemonic | Operands | Type | Description |
|---|---|---|---|
| `JMP` | `<target>` | Branch | Jump unconditionally to word address `<target>` |
| `JMP.EQ` | `<target>` | Branch | Jump if Equal (`EQ == 1` / zero) |
| `JMP.NE` | `<target>` | Branch | Jump if Not Equal (`EQ == 0` / non-zero) |
| `JMP.LO` | `<target>` | Branch | Jump if Low (`LO == 1` / negative) |
| `JMP.HI` | `<target>` | Branch | Jump if High (`LO == 0 && EQ == 0` / positive non-zero) |
| `JMPL` | `<target>` | Branch | **Jump and Link**: stores return jump at `<target>`, jumps to `<target> + 2` |
| `IJMP.ON` | `reg, bit, target` | Rel Branch | Relative jump if bit `bit` (0..15) of `reg` (R0..R3) is `1` |
| `IJMP.OFF`| `reg, bit, target` | Rel Branch | Relative jump if bit `bit` (0..15) of `reg` (R0..R3) is `0` |
| `TBIT` | `reg, bit` | Bit Op | Test bit in register (R0..R31); updates `EQ`/`LO` flags |
| `SBIT` | `reg, bit` | Bit Op | Set bit to `1` in register (R0..R31); updates `EQ`/`LO` flags |
| `RBIT` | `reg, bit` | Bit Op | Reset bit to `0` in register (R0..R31); updates `EQ`/`LO` flags |
| `PBIT` | `reg, bit` | Bit Op | Preserve/sample bit in register (R0..R31); updates `EQ`/`LO` flags |
| `MOVI` | `reg, imm8` | Immediate | Load unsigned 8-bit literal into even register (R0..R30), clear high byte |
| `ADDI` | `reg, imm8` | Immediate | Add unsigned 8-bit literal to even register (R0..R30) |
| `SUBI` | `reg, imm8` | Immediate | Subtract unsigned 8-bit literal from even register (R0..R30) |
| `CMPI` | `reg, imm8` | Immediate | Compare even register (R0..R30) with 8-bit literal (updates flags only) |
| `MOV` | `pReg, qReg` | Reg Arith | Copy 16-bit word: `pReg = qReg` (R0..R31) |
| `ADD` | `pReg, qReg` | Reg Arith | 16-bit addition: `pReg = pReg + qReg` (R0..R31) |
| `SUB` | `pReg, qReg` | Reg Arith | 16-bit subtraction: `pReg = pReg - qReg` (R0..R31) |
| `CMP` | `pReg, qReg` | Reg Arith | 16-bit compare: `pReg - qReg` (updates flags only) |
| `MOVB` | `dest, src` | Byte Indirect | Transfer memory/register byte (R0..R15; supports `[Rn]` and `[Rn]++`) |
| `CMPB` | `dest, src` | Byte Indirect | Compare memory/register bytes (R0..R15; supports `[Rn]` and `[Rn]++`) |
| `IN` | `dev, reg` | I/O | Read 1 byte from peripheral into register low byte (clears high byte) |
| `OUT` | `dev, src` | I/O | Output 1 byte from register or `[reg]` to peripheral |
| `OUTM` | `dev, [reg]` | I/O | Begin background Multiplex printing from `[reg]` to printer |
| `.ORG` | `<addr>` | Directive | Set program counter location counter |
| `.WORD` | `<expr>` | Directive | Emit 16-bit word literal or symbol |
| `.BYTE` | `<val>` | Directive | Emit 8-bit byte value |
| `.SPACE`| `<count>` | Directive | Reserve `<count>` zero bytes |
