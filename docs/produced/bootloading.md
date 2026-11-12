### 1. The IBM Engineering Rationale: "No Special Program-Loading Hardware"

The *IBM Journal of Research and Development* (Vol. 12, No. 5, September 1968, pp. 8–9, *"Development of the Magnetic Tape SELECTRIC Composer"*) directly explains why this routine is designed this way:

> *"No hardware is provided specifically for loading the program. A short (3-step) routine capable of loading a program can be manually entered at the control panel by service personnel.*
>
> *Although there are several variations of the application program, they all contain an identical portion capable of loading factory-recorded program tapes. This routine can be accessed by normal operator actions at the keyboard. This avoids the need for operator knowledge of the internal operations or instructions of the machine and any need for special program-loading hardware. Special circuits monitor the logic power supply voltage and shut the machine down in case of power failure to prevent loss of the program. The power-on sequence always starts the machine at the same instruction [byte 546] to avoid any operator intervention in addressing."*

#### The Parameter Bits (`p` and `m`):

The lowercase letters in the table allowed the CE to select the boot device:
* **`p` bit** (bit 8 of byte 42):
  * `p = 0`: **Magnetic Tape Reader**
  * `p = 1`: **Paper Tape Reader** (Model 6310)
* **`m` bit** (bit 1 of byte 43):
  * `m = 0`: **Station 1** (Right Tape Transport)
  * `m = 1`: **Station 2** (Left/Merge Tape Transport)

---

### What You Can Do With It in the Web UI

In the emulator, you can interact with the Initial Loader in several ways:

1. **One-Click Cold Boot (`Zero RAM` + `Key Left` / `Key Right` + `Mount Program Tape`)**:
   * Click **`Zero RAM`** on the CE `INITIAL LOADER` panel (or **`Zero Core Memory`** in the Core Memory inspector) to wipe all 2,048 bytes of Core Memory to `0x00`.
   * Click **`Key Left`** (`m = 1`) or **`Key Right`** (`m = 0`) to write bytes `40..47` into Core Memory and set `PSAR = 42` (`0x02A`).
   * Click **`Program`** on the Left or Right Tape Station (or **`Mount Program Tape`**) to mount a 2,083-track 8-bit odd-parity Factory Program Tape, then click **`Run Composition`** to watch Core Memory populate and stop ready at `0x222` (`546`).
   * Mount a manuscript text tape (**`Sample`** or custom text) and click **`Run Composition`** (or press **`START`** / **`0`**) to compose camera-ready galley output from the freshly booted program!

2. **Inspect the Bootstrap in the Hardware Drawer**:
   * Open the bottom drawer (**"Customer Engineer CCU 6320 Hardware Internals"**).
   * In the **Core Memory** viewer or **Live Disassembly** viewer, you can inspect addresses `0x028`–`0x02F` (`40`–`47`) to see the raw hex and decoded assembly instructions live.

3. **Step Through the Bootstrap**:
   * With the faceplate removed (CE mode), slide the bottom left **Clock Control** switch to **`Bit`**, **`Word`**, or **`P.S.`** (Program Step).
   * Click **Step** to watch the CCU step through the bootstrap routine instruction-by-instruction, observing the lamps (`Bit Ctr B/A`, `ID..IA`, and register buses) update with each cycle.

4. **Manual Keying (Authentic 1967 CE Experience — ILD 0308, 0312 & Page 0603)**:
   * On the real 6310/6320, the 11 console keys **`0, 1, 2 | 3, 4, 5, 6 | 7, 8, 9, R`** sit directly beneath Lamps `1..11`:
     * **`Addr` mode** (forces **`Display 1`**, `Y 11..Y 1`): **`Clear`** zeros `PSAR` (`0x000`); keys **`0..9, R`** OR bits `10..0` (`+1024, +512, +256 | +128, +64, +32, +16 | +8, +4, +2, +1`) into `PSAR` (allowing both even and odd byte addresses `0..2047`).
     * **`Data` mode** (forces **`Display 2`**, `O 8..O 1`): **`Clear`** zeros the single byte at `[PSAR]`; keys **`3, 4, 5, 6`** OR the high nibble (`O 8..O 5`) and keys **`7, 8, 9, R`** OR the low nibble (`O 4..O 1`) directly into `[PSAR]`, matching the two 4-bit columns on the silk-screened `INITIAL LOADER` table!
   * For example, to enter `PSAR = 42` (`32 + 8 + 2`): slide to **`Addr`**, press **`Clear`**, press **`5`**, **`7`**, **`9`**, then slide back to **`Proc`**.

### 1. What the 8-Byte "Initial Loader" Actually Is

When decoded according to the actual hardware bit positions in Figure 10 of the manual, the 8 bytes (`40..47`) form a complete, self-contained **auto-incrementing memory loader**:

|             Byte Address             |      Binary (`B` `A`)      | 16-Bit Word | Real Hardware Instruction | Purpose                                                                                                                                                                                         |
|:------------------------------------:|:--------------------------:|:-----------:|:--------------------------|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **40 (`0x028`)**<br>**41 (`0x029`)** | `0000 0000`<br>`0000 0000` |  `0x0000`   | **`R20 = 0x0000`**        | Initializes **Register 20** (at byte address 40) to **`0`**. This is not a NOP—`R20` is the **16-bit destination address pointer**!                                                             |
| **42 (`0x02A`)**<br>**43 (`0x02B`)** | `p000 0010`<br>`0001 100m` |  `0x1802`*  | **`IN Tape, R1`**         | Reads one byte from the selected tape reader into **Register 1** (at byte address `2`).                                                                                                         |
| **44 (`0x02C`)**<br>**45 (`0x02D`)** | `0000 0011`<br>`0111 1010` |  `0x7A03`   | **`MOVB [R20]+, R1`**     | **P-Indirect Byte Transfer with Bump-by-1**: copies the byte from `R1` (byte address `2`) into the core memory byte pointed to by **`R20`** (byte address `40`), and **increments `R20` by 1**! |
| **46 (`0x02E`)**<br>**47 (`0x02F`)** | `0010 1010`<br>`0000 0000` |  `0x002A`   | **`JMP 0x02A`**           | Unconditional branch back to address **`42` (`0x02A`)** to read the next byte from tape.                                                                                                        |

Here is the bit-by-bit breakdown of Word 44 (`0x7A03` = `0111 1010 0000 0011`):
$$\underbrace{01}_{\text{Arith/Byte}} \;\; \underbrace{1}_{\text{Transfer}} \;\; \underbrace{1}_{\text{Bump}+1} \;\; \underbrace{10100}_{\text{P} = 32+8 = \mathbf{40}\text{ (R20)}} \;\; \underbrace{0}_{\text{Q Direct}} \;\; \underbrace{00001}_{\text{Q} = \mathbf{2}\text{ (R1)}} \;\; \underbrace{1}_{\text{P Indirect}}$$

Everything in the 8-byte table has a purpose:
* **Bytes 40–41** (`R20`) hold the **current write address** in core memory (starting at byte `0`).
* **Bytes 42–43** read the next tape character into **`R1`** (byte `2`).
* **Bytes 44–45** store that character into **`[R20]`** and bump **`R20`** by `1`.
* **Bytes 46–47** loop back to **`42`**.

---

### 2. What Must Be on the Tape to Load and Start a Program?

Because the MT/SC has a unified 2,048-byte core memory where working registers (`0..63`), tables (`64..197`), buffers (`198..465`), and program instructions (`466..2041`) all live in the same byte-addressable space, the Initial Loader starts writing tape bytes sequentially at **byte `0`** (`R20 = 0x0000`).

To see what the tape must contain, we have to look at what happens when `R20` advances from `0` up toward `40..47` (where `R20` and the Initial Loader itself live!).

From **Figure 5** of the 1968 IBM paper (*Word time sequences for instructions*, p. 8), the hardware executes `MOVB [R20]+, R1` (`Byte` with `Indirect P`) in this exact micro-sequence:
* **`I6`**: Reads the incoming tape byte from `R1` into the `B-Word` (`BW`).
* **`I7–I8`**: Reads the 16-bit pointer from `R20` (bytes `40..41`) into the `A-Word` (`AW`), **adds 1 to it, and writes `R20 + 1` back into `R20` during `I8`**.
* **`I9`**: Transfers the un-incremented address from `AW` into the Memory Address Register (`MAR`).
* **`I10`**: Writes the tape byte from `BW` into the memory byte selected by `MAR`.

Notice the critical detail: **`R20` is incremented in `I8` *before* the tape byte is written to memory in `I10`!**

This gives a program tape two ways to load memory and start execution:

#### Stage 1: Bytes `0..39` on the Tape

The first 40 bytes on the tape are written directly into core memory addresses `0..39` (`R0..R19`).
* Note that `R1` (bytes `2..3`) is used as the temporary 1-byte buffer by `IN Tape, R1`, so bytes `2..3` get overwritten on each subsequent tape read.
* Bytes `4..39` (18 words) can hold either initial register/table data **or** a secondary loader routine.

#### Stage 2: Bytes `40..41` on the Tape (Redirecting or Continuing the Pointer `R20`)

When `R20` reaches `40` (`0x0028`), the destination `[R20]` is **the pointer `R20` itself**:
1. **On Tape Byte #40** (`MAR = 40`, low byte of `R20`):
   * In `I8`, `R20` is bumped from `0x0028` (`40`) to `0x0029` (`41`).
   * In `I10`, Tape Byte #40 is written into byte `40` (overwriting the low byte of `R20`!).
2. **On Tape Byte #41**:
   * Because byte `40` was just overwritten by Tape Byte #40, the tape has **full control over where `R20` points next**!

Depending on what you want the tape to do, there are two patterns for bytes `40+`:

---

#### Pattern A: Direct Boot into a Small Routine (or Secondary Loader in Low Memory)

If the program (or secondary loader) fits in bytes `4..39` (or if you just want to test booting from `42..47`):
* **Tape Bytes `0..3`** = initial values for `R0..R1`.
* **Tape Bytes `4..39`** = your routine's instructions (starting at byte `4`, `R2`).
* **Tape Byte #40** = `0x2E` (`46`) — redirects `R20` directly to byte `46` (skipping over `42..45` so the `IN` and `MOVB` instructions aren't clobbered mid-loop!).
* **Tape Byte #41** = low byte of `JMP 0x004` (`0x04`), written to byte `46` (`R20` bumps to `47`).
* **Tape Byte #42** = high byte of `JMP 0x004` (`0x00`), written to byte `47`.
  * At the end of this `MOVB` instruction at `44`, the CPU steps to address `46`.
  * Address `46..47` now contains `0x0004` (**`JMP 0x004`** instead of `JMP 0x02A`)!
  * The CPU immediately branches to **`0x004`** and starts executing the loaded program!

---

#### Pattern B: Full Factory Program Tape (`TapeCartridge.fromCoreImage`)

Using only the 8-byte `INITIAL LOADER` at `40..47`, `TapeCartridge.fromCoreImage` encodes any 2,048-byte `CoreImage` into a 2,083-frame 8-bit odd-parity stream:
1. **Pass 1 (`40` bytes `0..39`)**: advances `R20` from `0` to `40`.
2. **Redirect (`1` byte at `40` = `48` / `0x30`)**: overwrites the low byte of `R20` so `R20` leaps over the live `INITIAL LOADER` (`41..47`) to `48`.
3. **Upper Core (`2,000` bytes `48..2047`)**: populates `R24..R31`, the proportional escapement table (`64..191`), line buffers, and microcode (`546..2047`), with a 2-byte `JMP 0x222` trampoline placed in the unused gap at `194..195` (`0x0C2`); `(2047 + 1) & 0x7FF` wraps `R20` back to `0`.
4. **Pass 2 (`40` bytes `0..39`)**: restores working registers `R0` and `R2..R19`, advancing `R20` back to `40`.
5. **Atomic Exit (`2` bytes)**: byte `46` (`0x2E`) redirects `R20` to `46`, and byte `194` (`0xC2`) patches byte `46` of `JMP 0x02A` into `JMP 0x0C2`, which immediately jumps through `194: JMP 0x222` to land cleanly at `PSAR = 546` (`0x222`).

---

### 3. Core Memory Preservation: What Is (and Isn't) Restored from the `CoreImage`

Because the MT/SC has a unified architecture where registers `R0..R31` occupy the first 64 bytes (`0..63`) of core memory, and the hardware Initial Loader executes out of physical memory while reading tape input, not all 2,048 bytes of an input `CoreImage` can be preserved during cold boot.

Exactly 12 bytes across three specific memory regions are skipped or overwritten during the process:

1. **The Initial Loader Workspace & Loop: Bytes `40..47` (`R20..R23`)**:
   - `image.bytes(40..47)` are **never emitted to the tape**.
   - These 8 bytes hold the active loader loop:
     - `40..41` (`R20`): destination pointer.
     - `42..43` (`R21`): `IN Tape, R1`.
     - `44..45` (`R22`): `MOVB [R20]+, R1`.
     - `46..47` (`R23`): `JMP 0x02A`.
   - Streaming `image.bytes(40..47)` would overwrite instructions while they are being fetched and executed by the CCU. The tape instead writes `48` (`0x30`) to byte `40` to make `R20` leap over `41..47`.
   - When bootloading completes, addresses `40..47` retain the Initial Loader instructions (with byte 46 patched to jump out upon exit). Any contents in `image.bytes(40..47)` are ignored.

2. **The Tape Input Staging Word: Bytes `2..3` (`R1`)**:
   - All tape frames are ingested into register `R1` via `IN Tape, R1` (`writeReg(R1, Word(byte & 0xff))`).
   - Although Pass 2 restores `0..39` from the `CoreImage`, the final **exit handshake** must read two more bytes from tape into `R1`:
     - Frame 2081 (byte `46`) to redirect `R20` to address 46.
     - Frame 2082 (byte `trampoline` / low byte of exit jump) to patch the `JMP 0x02A` at address 46.
   - At boot completion, address 2 contains the low byte of the trampoline jump target (e.g. `194` / `0xC2`), and address 3 contains `0x00`. Whatever was in `image.bytes(2..3)` is clobbered.

3. **The Trampoline Slot: Bytes `194..195` (when `entryPoint >= 256`)**:
   - The exit jump instruction at byte 46 has an 8-bit page-0 target displacement (`< 256`). It cannot directly jump to high memory (such as power-on microprogram entry at `546` / `0x222`).
   - [`TapeCartridge.fromCoreImage`](../../core/shared/src/main/scala/mtsc/periph/TapeCartridge.scala#L176-L189) dynamically finds an unused 2-byte slot in low memory (preferring `194..195`, which sits in the unused gap between proportional escapement tables at `64..191` and line buffers starting at `198`), and places a `JMP entryPoint` instruction there.
   - Core memory at `194..195` therefore contains the 2-byte trampoline jump rather than whatever original data was in `CoreImage`.

#### Core Memory State Summary at Boot Completion

|       Memory Range        | Hardware Role                 |   Preserved from `CoreImage`?    | Contents in Core Memory at Boot Completion                 |
|:-------------------------:|:------------------------------|:--------------------------------:|:-----------------------------------------------------------|
|     **`0..1`** (`R0`)     | Working Register 0            |             **Yes**              | Restored by Pass 2                                         |
|     **`2..3`** (`R1`)     | Tape Input Staging Register   |              **No**              | Byte 2 = last tape frame (exit target); Byte 3 = `0x00`    |
|  **`4..39`** (`R2..R19`)  | Working Registers 2..19       |             **Yes**              | Restored by Pass 2                                         |
| **`40..47`** (`R20..R23`) | Initial Loader Workspace      |              **No**              | Retains Initial Loader code (`R20 = 47`, `JMP trampoline`) |
|       **`48..193`**       | Escapement tables & registers |             **Yes**              | Loaded in Upper Core pass                                  |
|      **`194..195`**       | Trampoline gap                | **No** (unless already matching) | `JMP entryPoint` (if `entryPoint >= 256`)                  |
|      **`196..2047`**      | Line buffers & microcode      |             **Yes**              | Loaded in Upper Core pass                                  |

Because of this hardware reality, MT/SC microcode (such as [`ContinuousComposerRoutine`](../../core/shared/src/main/scala/mtsc/diag/ContinuousComposerRoutine.scala#L48-L50)) starts by explicitly re-initializing `R20`, `R22`, and any working registers it needs, never relying on low bootstrap registers surviving cold boot.
