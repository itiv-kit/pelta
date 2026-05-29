# Minimal Systolic Array Accelerator — Architecture Documentation

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [System Architecture](#2-system-architecture)
3. [Module Descriptions](#3-module-descriptions)
   - [PE — Processing Element](#31-pe--processing-element)
   - [Array — Systolic Array](#32-array--systolic-array)
   - [Buffer — Memory Buffer](#33-buffer--memory-buffer)
   - [Controller — Control Unit](#34-controller--control-unit)
   - [Top — Top-Level Integration](#35-top--top-level-integration)
   - [AXILiteWrapper — AXI4-Lite Slave](#36-axilitewrapper--axi4-lite-slave)
   - [SafetyMode — Safety Variant Selector](#37-safetymode--safety-variant-selector)
   - [SafePE — PE Dispatcher](#38-safepe--pe-dispatcher)
   - [ParityPE — Parity-Shadowed PE](#39-paritype--parity-shadowed-pe)
   - [TMRPE — Triple-Modular-Redundant PE](#310-tmrpe--triple-modular-redundant-pe)
4. [Data Flow](#4-data-flow)
5. [Timing](#5-timing)
6. [Known Limitations](#6-known-limitations)
7. [Build & Test](#7-build--test)

---

## 1. Project Overview

This accelerator implements a **weight-stationary systolic array** for efficient hardware
computation of vector-matrix and matrix-matrix multiplications.

### What is a Systolic Array?

A systolic array is a regular arrangement of simple processing units (Processing Elements,
PEs) that pass data through themselves in a clock-driven pipeline. The name comes from
biology: similar to a heartbeat, data is rhythmically pumped through the system.

**Advantages over sequential software computation:**
- High throughput through parallel MAC operations
- Low memory bandwidth: data passes between neighbouring PEs rather than travelling back to
  a central memory
- Simple, regular connection structure that synthesises well on FPGAs and ASICs

### Purpose

The accelerator computes operations of the form `y = v × W`, where:
- `v` is an input vector (activation vector)
- `W` is a weight matrix (permanently stored in the PEs)
- `y` is the output vector (result vector)

Full matrix-matrix multiplications `C = A × B` are performed by running one pass per row of
`A`. When the activation dimension exceeds the array height, multiple activation tiles can be
streamed through consecutive passes and their results summed inside the accelerator using the
`accumulate` mode.

---

## 2. System Architecture

### Block Diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│                           Top-Level                                   │
│                                                                       │
│  ┌──────────────┐   ┌──────────────────┐   ┌───────────────────────┐ │
│  │ Weight Buffer│   │   Controller     │   │  Activation Buffer    │ │
│  │  (256×8 bit) │   │     (FSM)        │   │    (256×8 bit)        │ │
│  └──────┬───────┘   └────────┬─────────┘   └──────────┬────────────┘ │
│         │ readData           │ loadWeights/            │ readData     │
│         │                   │ enable/clearAccum        │              │
│         ▼                   ▼                          ▼              │
│  ┌──────────────────────────────────────────────────────────────┐    │
│  │                      Systolic Array                           │    │
│  │                                                               │    │
│  │  weightData ──►  PE[0][0] ──► PE[0][1] ──► PE[0][2] ──► ... │    │
│  │  (rows×cols)        │            │            │               │    │
│  │                     ▼            ▼            ▼               │    │
│  │               PE[1][0] ──► PE[1][1] ──► PE[1][2] ──► ...    │    │
│  │                     │            │            │               │    │
│  │                     ▼            ▼            ▼               │    │
│  │               PE[2][0] ──► PE[2][1] ──► PE[2][2] ──► ...    │    │
│  │                     │            │            │               │    │
│  │                     ▼            ▼            ▼               │    │
│  │              partialSumsOut[0] [1]         [2]  ...           │    │
│  └──────────────────────────────────────────────────────────────┘    │
│         │ tileAccumRegs (optional tile accumulation)                  │
│         ▼                                                             │
│  ┌──────────────────┐                                                 │
│  │  Result Buffer   │                                                 │
│  │  (256×32 bit)    │                                                 │
│  └──────────────────┘                                                 │
└──────────────────────────────────────────────────────────────────────┘
```

The AXI4-Lite wrapper (`AXILiteWrapper`) sits above the Top-Level module and connects a
processor or DMA engine to the same control and data signals through a standard bus interface.

### Data Flow in the Array

```
Activations  → horizontal (left to right, one per row)
Partial Sums → vertical   (top to bottom, one per column)
Weights      → stationary, stored in each PE's weight register
```

```
activations[0] ──► PE[0][0] ──activationOut──► PE[0][1] ──► PE[0][2]
                       │                            │              │
                   partialSumOut               partialSumOut  partialSumOut
                       │                            │              │
activations[1] ──► PE[1][0] ──activationOut──► PE[1][1] ──► PE[1][2]
                       │                            │              │
                   partialSumOut               partialSumOut  partialSumOut
                       │                            │              │
                   result[0]                    result[1]     result[2]
```

`activationOut` is delayed by one cycle (registered), so each activation arrives at column
`c` exactly `c` cycles after it was presented to column 0 of the same row.

---

## 3. Module Descriptions

### 3.1 PE — Processing Element

**File:** `src/main/scala/accelerator/PE.scala`

The PE is the fundamental compute unit. It implements a **Multiply-Accumulate (MAC)
operation** in weight-stationary mode: the weight is loaded once and remains stationary while
activations stream through.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `dataWidth` | 8 | Bit width of weight and activation inputs |
| `saturate` | false | Enable signed saturation on accumulator overflow |

#### IO Signals

| Signal | Direction | Width | Description |
|---|---|---|---|
| `weightIn` | Input | `dataWidth` | Weight value to load |
| `activationIn` | Input | `dataWidth` | Incoming activation (signed) |
| `activationOut` | Output | `dataWidth` | Forwarded activation, delayed by one clock cycle |
| `partialSumIn` | Input | `2*dataWidth+16` | Incoming partial sum from the PE above |
| `partialSumOut` | Output | `2*dataWidth+16` | Outgoing partial sum to the PE below |
| `loadWeight` | Input | 1 | When high: latch `weightIn` into `weightReg` |
| `enable` | Input | 1 | When high: execute one MAC cycle |
| `clearAccum` | Input | 1 | When high: reset accumulator to zero (takes priority over `enable`) |

All data signals are signed (`SInt`).

#### Internal Registers

| Register | Width | Description |
|---|---|---|
| `weightReg` | `dataWidth` | Stored weight, written when `loadWeight` is high |
| `accumulator` | `2*dataWidth+16` | Holds the result of the most recent MAC operation |

#### Behaviour

```
Each clock cycle:

  if clearAccum:
      accumulator ← 0

  else if enable:
      product = weightReg × activationIn
      rawSum  = product + partialSumIn     // computed with one extra bit width
      if saturate:
          if signed overflow detected: accumulator ← INT_MAX or INT_MIN
          else:                        accumulator ← rawSum[accWidth-1:0]
      else:
          accumulator ← rawSum[accWidth-1:0]

  activationOut ← RegNext(activationIn)   // registered, one-cycle delay
  partialSumOut ← accumulator             // combinational, reads last stored value
```

The PE does **not** add to its accumulator across cycles. Each `enable` cycle replaces the
accumulator with a fresh computation (`weight × activation + partialSumIn`). Accumulation
across the row dimension happens through the vertical partial-sum flow, not through temporal
accumulation within the PE.

The accumulator retains its last value between compute cycles (when neither `enable` nor
`clearAccum` is asserted). This keeps results visible for the result-capture logic after
computation ends. The `clearAccum` signal must be asserted at the start of each new pass to
prevent stale accumulators from contributing to the next result.

**Overflow detection (when `saturate=true`):** The MAC uses Chisel's `+&` operator, which
produces a result one bit wider than its operands. If the extra carry bit and the MSB of the
result differ, a signed overflow has occurred. The result is clamped to `Int.MaxValue`
(0x7FFFFFFF) on positive overflow or `Int.MinValue` (0x80000000) on negative overflow.

**Partial sum bit width:** `2*dataWidth + 16` prevents accumulator overflow for large arrays.
With the default `dataWidth=8`, this gives 32 bits — large enough for up to 65536
accumulations of 16-bit products without overflow when saturation is disabled.

---

### 3.2 Array — Systolic Array

**File:** `src/main/scala/accelerator/Array.scala`

The Array instantiates a `rows × cols` grid of PEs and wires them in the systolic pattern.
Each PE is constructed via the [`SafePE`](#38-safepe--pe-dispatcher) wrapper, so the array
transparently supports the three safety variants (`NoSafety`, `Parity`, `TMR`) without
any change to the wiring.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `rows` | 4 | Number of rows |
| `cols` | 4 | Number of columns |
| `dataWidth` | 8 | Bit width of data paths |
| `saturate` | false | Passed through to each PE (effective only in `NoSafety` mode) |
| `safety` | `NoSafety` | Selects PE variant: `NoSafety`, `Parity`, or `TMR` |

#### IO Signals

| Signal | Direction | Width | Description |
|---|---|---|---|
| `weightData` | Input | `rows × cols × dataWidth` | Weight matrix, presented to all PEs simultaneously |
| `loadWeights` | Input | 1 | Broadcast `loadWeight` to all PEs |
| `activations` | Input | `rows × dataWidth` | Activation vector — one value per row, entering from the left |
| `partialSumsIn` | Input | `cols × (2*dataWidth+16)` | Initial partial sum values entering from the top (typically 0) |
| `partialSumsOut` | Output | `cols × (2*dataWidth+16)` | Result vector from the last row |
| `enable` | Input | 1 | Broadcast `enable` to all PEs |
| `clearAccum` | Input | 1 | Broadcast `clearAccum` to all PEs |
| `errorDetected` | Output | 1 | OR-reduction of `errorDetected` across every PE in the grid. Tied to 0 in `NoSafety` mode. |

#### Wiring Scheme

```scala
// Activation flow (horizontal, left → right):
PE[r][0].activationIn  := activations[r]
PE[r][c].activationIn  := PE[r][c-1].activationOut   // for c > 0

// Partial sum flow (vertical, top → bottom):
PE[0][c].partialSumIn  := partialSumsIn[c]
PE[r][c].partialSumIn  := PE[r-1][c].partialSumOut   // for r > 0

// Array output:
partialSumsOut[c]      := PE[rows-1][c].partialSumOut
```

---

### 3.3 Buffer — Memory Buffer

**File:** `src/main/scala/accelerator/Buffer.scala`

The Buffer implements a simple SRAM-style memory with separate read and write ports.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `depth` | 256 | Number of entries |
| `width` | 32 | Bit width of each entry |

#### IO Signals

| Signal | Direction | Width | Description |
|---|---|---|---|
| `writeEnable` | Input | 1 | Enable write |
| `writeAddr` | Input | `log2Ceil(depth)` | Write address |
| `writeData` | Input | `width` | Data to write |
| `readEnable` | Input | 1 | Enable read |
| `readAddr` | Input | `log2Ceil(depth)` | Read address |
| `readData` | Output | `width` | Read data — valid one cycle after `readEnable` is asserted |
| `full` | Output | 1 | Entry counter has reached `depth` |
| `empty` | Output | 1 | Entry counter is zero |

#### Implementation Details

- Uses Chisel `SyncReadMem` with `WriteFirst` mode: on a simultaneous read and write to the
  same address, the newly written data is returned on the read port in the same cycle.
- The `entryCount` register tracks written entries and drives `full` / `empty`. It is
  incremented on writes and decremented on reads. It does not track overwrites correctly
  (writing to an already-occupied address still increments the counter), so the Buffer is
  used as a direct-addressed SRAM rather than a FIFO queue.

---

### 3.4 Controller — Control Unit

**File:** `src/main/scala/accelerator/Controller.scala`

The Controller is a Moore FSM that orchestrates the sequence of a computation.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `rows` | 4 | Number of rows (affects weight load count) |
| `cols` | 4 | Number of columns (affects compute duration) |

#### IO Signals

| Signal | Direction | Description |
|---|---|---|
| `start` | Input | Assert for one cycle to begin a computation |
| `loadWeights` | Input | When high: load weights before computing |
| `busy` | Output | High whenever the FSM is not in Idle |
| `done` | Output | High when the FSM is in Done (computation complete) |
| `arrayLoadWeights` | Output | Drives `Array.io.loadWeights` |
| `arrayEnable` | Output | Drives `Array.io.enable` |
| `arrayClearAccum` | Output | Drives `Array.io.clearAccum` — asserted for one cycle on every Idle → Compute or Idle → LoadWeights transition |
| `readActivation` | Output | Tells Top to advance the activation buffer read pointer |
| `writeResult` | Output | Tells Top to begin capturing results |

#### State Diagram

```
         start && loadWeights              start && !loadWeights
              ┌──────────────┐               ┌─────────────────┐
              │              │               │                 │
              ▼              │               ▼                 │
   ┌────────────────────┐    │    ┌────────────────────┐       │
   │        IDLE        │◄───┘    │       IDLE         │◄──────┘
   │                    │         │                    │
   │  arrayClearAccum=1 │         │  arrayClearAccum=1 │
   └────────┬───────────┘         └────────┬───────────┘
            │ → LOAD_WEIGHTS               │ → COMPUTE
            ▼                              ▼
   ┌──────────────────┐          ┌──────────────────┐
   │  LOAD_WEIGHTS    │          │     COMPUTE      │
   │                  │          │                  │
   │  arrayLoadWeights│          │  arrayEnable     │
   │  loadCounter     ├─────────►│  readActivation  │
   │  0..rows*cols    │ loadDone │  cycleCounter    │
   └──────────────────┘          │  0..rows+cols    │
                                 └────────┬─────────┘
                                          │ computeDone
                                          ▼
                                 ┌──────────────────┐
                                 │      DONE        │
                                 │  writeResult=1   │
                                 └────────┬─────────┘
                                          │ !start
                                          ▼
                                         IDLE
```

#### Output Signals per State

| State | `arrayLoadWeights` | `arrayEnable` | `arrayClearAccum` | `readActivation` | `writeResult` |
|---|---|---|---|---|---|
| IDLE (inactive) | 0 | 0 | 0 | 0 | 0 |
| IDLE (start asserted) | 0 | 0 | 1 | 0 | 0 |
| LOAD_WEIGHTS | 1 | 0 | 0 | 0 | 0 |
| COMPUTE | 0 | 1 | 0 | 1 | 1 (after delay) |
| DONE | 0 | 0 | 0 | 0 | 1 |

`writeResult` in COMPUTE is asserted `cols - 1` cycles before `computeDone`. This accounts
for the pipeline depth of the last column: results from column `c` are valid `c` cycles after
column 0 produces its first result.

#### Timing Parameters

```scala
val computeCycles = (rows + cols).U          // derived from array dimensions
val loadDone      = loadCounter === (rows * cols + 1).U
val startWrite    = cycleCounter >= computeCycles - cols.U + 1.U
val computeDone   = cycleCounter >= computeCycles
```

`rows + cols` compute cycles are required because activations need `cols` cycles to cross the
array horizontally and partial sums need `rows` cycles to propagate vertically. These pipeline
delays overlap.

---

### 3.5 Top — Top-Level Integration

**File:** `src/main/scala/accelerator/Top.scala`

Top connects all submodules, manages the time-multiplexed loading of weights and activations,
handles result capture and tile accumulation, and presents the external control interface.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `rows` | 4 | Array rows |
| `cols` | 4 | Array columns |
| `dataWidth` | 8 | Data bit width |
| `bufferDepth` | 256 | Depth of all three buffers |
| `saturate` | false | Passed to Array and its PEs (effective only in `NoSafety` mode) |
| `safety` | `NoSafety` | Forwarded to Array; selects the PE variant (`NoSafety` / `Parity` / `TMR`) |

#### External IO

| Signal | Direction | Description |
|---|---|---|
| `start` | Input | Assert for one cycle to start a computation |
| `loadWeights` | Input | Load weights from buffer into PEs before computing |
| `accumulate` | Input | When high: add array output to tile accumulator registers instead of overwriting them |
| `weightWriteEnable` | Input | Write enable for the weight buffer |
| `weightWriteAddr` | Input | Weight buffer write address |
| `weightWriteData` | Input | Weight data (unsigned; interpreted as signed in the PE) |
| `activationWriteEnable` | Input | Write enable for the activation buffer |
| `activationWriteAddr` | Input | Activation buffer write address |
| `activationWriteData` | Input | Activation data |
| `resultReadEnable` | Input | Read enable for the result buffer |
| `resultReadAddr` | Input | Result buffer read address |
| `resultReadData` | Output | Signed result data (`SInt`), valid one cycle after `resultReadEnable` |
| `busy` | Output | High while computation is in progress |
| `done` | Output | High when computation is complete |
| `errorDetected` | Output | High whenever any PE in the array reports a fault (driven by `Array.io.errorDetected`); always 0 in `NoSafety` mode |

#### Weight Loading Sequence

Weights are loaded from the weight buffer into the PE array one at a time. The controller
asserts `arrayLoadWeights` for `rows * cols + 1` cycles, driving `weightLoadCounter` from 0
to `rows * cols`. On each cycle, the weight at address `weightLoadCounter` is read from the
buffer and routed to the corresponding PE's weight register.

Address mapping (row-major):
```
buffer address = row * cols + col  →  PE[row][col].weightReg
```

The `RegNext` delay on `targetRow` / `targetCol` compensates for the one-cycle read latency
of the synchronous `SyncReadMem`.

#### Activation Distribution

Activations are read from the activation buffer one per cycle. The `activationCounter`
register advances from 0 to `rows - 1` during the compute phase while `readActivation` is
asserted. The counter stops when it reaches `rows` so only the first `rows` entries are read:

```
activationReadActive = controller.io.readActivation && activationCounter < rows
```

On each cycle where `activationReadActive` is high, the buffer is read at `activationCounter`.
One cycle later, when the data is available from `SyncReadMem`, it is latched into the
`activationRegs` register for the corresponding row (matched by `RegNext(readAddr)`). The
array is driven from `activationRegs` continuously, keeping values stable throughout the
compute phase even after the counter stops.

#### Result Capture and Tile Accumulation

Result capture begins when the controller's `writeResult` signal rises (detected as a
leading-edge pulse via `resultWriteStart`). A two-step staging process is used:

1. **Staging delay (`resultCaptureDelay`):** `cols - 1` cycles are counted down from
   `resultWriteStart`. This delay accounts for the pipeline skew: column `c` produces its
   final result `c` cycles after column 0, so the last column's result is valid exactly
   `cols - 1` cycles after the first.

2. **Snapshot capture:** When the delay reaches zero, all `cols` array outputs are read
   simultaneously and written into `tileAccumRegs` (and `stagedResultData`). If
   `accumulate=true`, the new values are added to the existing `tileAccumRegs`; otherwise
   `tileAccumRegs` is overwritten. `resultWriteActive` is then set.

3. **Sequential buffer write:** While `resultWriteActive` is high, `stagedResultData[c]` is
   written to `resultBuffer[c]` on each of `cols` consecutive cycles, then `resultWriteActive`
   is cleared.

`tileAccumRegs` are `cols` signed registers of width `2*dataWidth + 16`. They accumulate
partial results across activation tiles when `accumulate=true` and are overwritten (not
reset) when `accumulate=false`.

Result data coming out of the result buffer is unsigned (`UInt`) internally and is cast to
`SInt` on `io.resultReadData` so the host receives signed 32-bit values.

#### Buffer Address Driving

All buffer `readEnable` and `readAddr` signals are driven exclusively inside `when`/
`otherwise` branches. There are no implicit default assignments for these signals, which
ensures the Chisel last-connect rule does not introduce unintended behaviour.

---

### 3.6 AXILiteWrapper — AXI4-Lite Slave

**File:** `src/main/scala/accelerator/AXILiteWrapper.scala`

`AXILiteWrapper` wraps Top with an AXI4-Lite slave interface. It allows a processor or DMA
controller to load data, start computations, and read results through a standard bus protocol.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `rows` | 4 | Passed to Top |
| `cols` | 4 | Passed to Top |
| `dataWidth` | 8 | Passed to Top |
| `bufferDepth` | 256 | Passed to Top |
| `saturate` | false | Passed to Top |
| `axiAddrWidth` | 8 | Bit width of AXI address channels |

#### AXI4-Lite IO Signals

| Signal | Direction | Description |
|---|---|---|
| `awaddr` / `awvalid` / `awready` | Input / Output | Write address channel |
| `wdata` / `wstrb` / `wvalid` / `wready` | Input / Output | Write data channel |
| `bresp` / `bvalid` / `bready` | Output / Input | Write response channel |
| `araddr` / `arvalid` / `arready` | Input / Output | Read address channel |
| `rdata` / `rresp` / `rvalid` / `rready` | Output / Input | Read data channel |

#### Register Map

| Offset | Name | R/W | Bits | Description |
|---|---|---|---|---|
| 0x00 | CTRL | W | [2:0] | [0]=`start`, [1]=`loadWeights`, [2]=`accumulate` |
| 0x04 | STATUS | R | [1:0] | [0]=`busy`, [1]=`done` (latched until next start) |
| 0x08 | WEIGHT_ADDR | W | address | Weight buffer write address |
| 0x0C | WEIGHT_DATA | W | data | Weight value — triggers one-cycle write enable |
| 0x10 | ACTIV_ADDR | W | address | Activation buffer write address |
| 0x14 | ACTIV_DATA | W | data | Activation value — triggers one-cycle write enable |
| 0x18 | RESULT_ADDR | W | address | Result read address — triggers one-cycle read enable |
| 0x1C | RESULT_DATA | R | data | Result data, valid one cycle after RESULT_ADDR write |

Only CTRL bits [0], [1], [2] and STATUS bits [0], [1] are implemented. All other bits read
as zero and writes have no effect.

#### Write FSM

The write FSM has three states, taking exactly 3 clock cycles per transaction:

| Cycle | State | Action |
|---|---|---|
| 1 | sWIdle → sWDecode | Both AW and W must be valid simultaneously. `awready` and `wready` go high; address and data are latched. |
| 2 | sWDecode → sWResp | The register decode fires. Write-enable pulses, `startPulse`, and combinational overrides are active in this cycle. |
| 3 | sWResp → sWIdle | `bvalid` is asserted. The FSM returns to idle when `bready` is sampled high. |

**Combinational overrides on CTRL write:** When the CTRL register (0x00) is written,
`loadWeights` and `accumulate` are driven combinationally to Top in the same cycle that
`startPulse` fires. This avoids a one-cycle lag from the stored registers (`loadWeightsReg`,
`accumulateReg`) that would otherwise miss the transition.

**`startPulse`** is a combinational wire that is high only during the `sWDecode` cycle when
address 0x00 is decoded with data bit 0 set. It is not registered, so it is naturally a
one-cycle pulse.

#### Read FSM

The read FSM has three states, taking exactly 3 clock cycles per transaction:

| Cycle | State | Action |
|---|---|---|
| 1 | sRIdle → sRData | `arvalid` accepted, address latched. |
| 2 | sRData → sRResp | The selected register is sampled into `rDataReg`. |
| 3 | sRResp → sRIdle | `rvalid` and `rdata` are asserted. FSM returns when `rready` is high. |

Readable registers: STATUS (0x04) and RESULT_DATA (0x1C).

**Done latching:** `top.io.done` is a one-cycle pulse. `doneLatched` captures this pulse and
holds it until the next `startPulse` clears it. This ensures the host can always read the
done state across the multi-cycle AXI read transaction, even if `done` has de-asserted by the
time the STATUS read completes.

**Result data pipeline:** `resultDataReg` is `RegNext(top.io.resultReadData.asUInt)`. Writing
RESULT_ADDR triggers `resultReadEnable` in the `sWDecode` cycle; the buffer's `SyncReadMem`
presents the data one cycle later; `RegNext` captures it. The host must therefore wait at
least two clock cycles after the RESULT_ADDR write before reading RESULT_DATA.

---

### 3.7 SafetyMode — Safety Variant Selector

**File:** `src/main/scala/accelerator/SafetyMode.scala`

`SafetyMode` is a Scala sealed trait that names the three PE variants the array can be
elaborated with. It is a pure Scala (elaboration-time) parameter — there is no runtime
multiplexing, and the unused variants are never instantiated in the generated hardware.

```scala
sealed trait SafetyMode
case object NoSafety extends SafetyMode   // baseline PE, no fault detection
case object Parity   extends SafetyMode   // ParityPE — encoded-register fault detector
case object TMR      extends SafetyMode   // TMRPE — triple-modular redundancy with voter
```

`Top` and `Array` accept a `safety: SafetyMode = NoSafety` parameter and forward it to
the `SafePE` dispatcher inside the array.

---

### 3.8 SafePE — PE Dispatcher

**File:** `src/main/scala/accelerator/SafePE.scala`

`SafePE` is a thin wrapper that exposes one uniform IO bundle and dispatches PE
construction on the `safety` parameter. Building the array as a grid of `SafePE`
instances means the systolic wiring in `Array` is identical across all three safety
variants — only the underlying PE module changes at elaboration.

#### Parameters

| Parameter | Default | Description |
|---|---|---|
| `dataWidth` | 8 | Bit width of weight and activation |
| `saturate` | false | Forwarded to the baseline PE; ignored by `ParityPE` and `TMRPE` (they do not currently expose a `saturate` option) |
| `safety` | `NoSafety` | Selects the underlying PE variant |

#### IO Signals

Identical to the baseline `PE` IO plus a single output:

| Signal | Direction | Width | Description |
|---|---|---|---|
| `errorDetected` | Output | 1 | Asserted by the wrapped variant; tied to 0 in `NoSafety` |

#### Dispatch

| `safety`   | Wrapped module | `errorDetected` source |
|---|---|---|
| `NoSafety` | `PE`         | `false.B` (no detector) |
| `Parity`   | `ParityPE`   | `parityReg ≠ xor_reduce(accumulator)` |
| `TMR`      | `TMRPE`      | Any replica disagrees with the voted output |

---

### 3.9 ParityPE — Parity-Shadowed PE

**File:** `src/main/scala/accelerator/safety/ParityPE.scala`

`ParityPE` replicates the baseline non-saturating PE datapath and adds a 1-bit
`parityReg` that shadows the accumulator. The parity bit is driven from the same
combinational result as the accumulator on the same clock edge, and `errorDetected` is
the disagreement between the stored parity bit and the current parity of the
accumulator's contents:

```
errorDetected = parityReg ≠ xor_reduce(accumulator)
```

This is the **encoded-register** pattern: the parity is committed *with* the protected
register on the same edge, so an upset that corrupts a bit inside the accumulator cell
itself — not just on its output port — causes the two to diverge on the very next read.

The baseline `PE` is intentionally **not** reused as a sub-module here. Reusing `PE` and
wrapping its output with a parity check would only detect faults on the read path and
would leave bit-flips inside the register storage undetected; instantiating the
datapath inline lets the parity bit shadow the storage cell directly.

#### IO Signals

| Signal | Direction | Width | Description |
|---|---|---|---|
| `weightIn` | Input | `dataWidth` | Weight value to load |
| `activationIn` | Input | `dataWidth` | Incoming activation (signed) |
| `activationOut` | Output | `dataWidth` | Forwarded activation, one-cycle delay |
| `partialSumIn` | Input | `2*dataWidth+16` | Incoming partial sum from above |
| `partialSumOut` | Output | `2*dataWidth+16` | Outgoing partial sum to below |
| `loadWeight` | Input | 1 | Latch `weightIn` into `weightReg` |
| `enable` | Input | 1 | Execute one MAC cycle |
| `clearAccum` | Input | 1 | Reset accumulator (and `parityReg`) to zero |
| `errorDetected` | Output | 1 | High while the accumulator's parity disagrees with `parityReg` |

#### Internal Registers

| Register | Width | Description |
|---|---|---|
| `weightReg` | `dataWidth` | Stored weight |
| `accumulator` | `2*dataWidth+16` | Protected MAC accumulator |
| `parityReg` | 1 | Expected parity of `accumulator` |

`clearAccum` resets both `accumulator` and `parityReg` to zero, keeping the invariant
intact across passes. `ParityPE` does not currently support the saturating-accumulator
mode of the baseline `PE`.

Fault-injection detection-coverage measurements are in
`sim/questa/results/ParityPE_fi.csv`; OOC synthesis area and timing numbers (against
the baseline `PE`) are in `vivado/results/synthesis_summary.csv`.

---

### 3.10 TMRPE — Triple-Modular-Redundant PE

**File:** `src/main/scala/accelerator/safety/TMRPE.scala`

`TMRPE` instantiates three baseline `PE` modules in lockstep — same weight, same
activation, same partial sum, same control — and votes bitwise on the `partialSumOut`
of the three replicas:

```scala
voted(i) = majority(pe0(i), pe1(i), pe2(i))
         = (pe0(i) & pe1(i)) | (pe1(i) & pe2(i)) | (pe0(i) & pe2(i))
```

The voted vector is the module's `partialSumOut`. `errorDetected` is asserted whenever
*any* of the three replicas disagrees with the voted output:

```
errorDetected = (voted ≠ pe0) ∨ (voted ≠ pe1) ∨ (voted ≠ pe2)
```

This catches single-replica divergences while still producing the correct majority
result — the array can continue computing under a single PE fault. `activationOut` is
taken from `pe0` (the replicas are functionally equivalent under fault-free operation).

Area cost is roughly 3× a baseline PE plus the voter; the per-module OOC numbers are
in `vivado/results/synthesis_summary.csv` and the fault-injection coverage trace is in
`sim/questa/results/TMRPE_fi.csv`.

---

## 4. Data Flow

### Vector-Matrix Multiplication: Step by Step

Given: vector `v = [3, 5]`, matrix `W = [[2, 4], [1, 3]]`

Expected: `v × W = [3*2+5*1, 3*4+5*3] = [11, 27]`

#### Step 1: Load weights into weight buffer (before start)

```
weightBuffer[0] = W[0][0] = 2
weightBuffer[1] = W[0][1] = 4
weightBuffer[2] = W[1][0] = 1
weightBuffer[3] = W[1][1] = 3
```

#### Step 2: Load activations into activation buffer (before start)

```
activationBuffer[0] = v[0] = 3   (row 0)
activationBuffer[1] = v[1] = 5   (row 1)
```

#### Step 3: Assert start with loadWeights=true, accumulate=false

**Controller: IDLE → LOAD_WEIGHTS** (arrayClearAccum=1 for this cycle)

```
Cycle 0: read weightBuffer[0]=2 → weightRegs[0][0] ← 2
Cycle 1: read weightBuffer[1]=4 → weightRegs[0][1] ← 4
Cycle 2: read weightBuffer[2]=1 → weightRegs[1][0] ← 1
Cycle 3: read weightBuffer[3]=3 → weightRegs[1][1] ← 3
Cycle 4: loadDone → Controller: LOAD_WEIGHTS → COMPUTE
```

#### Step 4: Computation

**Controller: COMPUTE** — `arrayEnable=1`, `readActivation=1`

```
Cycle 0: activationCounter=0 → buffer read issued (data available next cycle)
         activationCounter=1 → buffer read issued (data available next cycle)

Cycle 1: activationRegs[0] ← 3, activationRegs[1] ← 5
         PE[0][0]: accumulator ← 2*3 + 0 = 6
         PE[1][0]: accumulator ← 1*3 + 0 = 3

Cycle 2: PE[0][1]: accumulator ← 4*3 + 0 = 12
                   (activation 3 reaches column 1 one cycle late)
         PE[1][0]: accumulator ← 1*5 + 0 = 5   (activation 5 for row 1)
         PE[1][1]: partialSumIn ← 12 (from PE[0][1])
                   accumulator ← 3*5 + 12 = 27

Cycle 3: PE[1][0]: partialSumOut = 5, feeds PE[2][0] (none in 2×2)
         partialSumsOut[0] = 5+6 is not right — see note below
```

Because partial sums from PE[0][c] only reach PE[1][c] with a one-cycle delay (registered
accumulator output), the final fully-propagated values appear at `partialSumsOut` after
`rows + cols = 4` total compute cycles.

#### Step 5: Read result

```
resultBuffer[0] = partialSumsOut[0] = 11   (3*2 + 5*1)
resultBuffer[1] = partialSumsOut[1] = 27   (3*4 + 5*3)
```

---

## 5. Timing

### Overview of a Complete Run (2×2 Array)

```
Phase:     │ LOAD_WEIGHTS (5 cyc) │ COMPUTE (4 cyc) │ DONE │
           │                      │                 │      │
start      ─┐
            └─────────────────────────────────────────────────
loadWeights ─┐
             └────────────────────────────────────────────────
arrayClearAccum  ─┐
                  └───────────────────────────────────────────
busy       ──────────────────────────────────────────────────┐
                                                             └─
done                                                      ───┐
                                                             └─ (until !start)
```

### Latency

| Phase | Duration for 2×2 | Duration for 4×4 |
|---|---|---|
| Weight loading | `rows * cols + 1` = 5 cycles | 17 cycles |
| Compute | `rows + cols` = 4 cycles | 8 cycles |
| Result write (after done) | `cols` cycles | 4 cycles |
| Total (excl. buffer fill) | ~9 cycles | ~25 cycles |

Result data appears in the result buffer `cols` cycles after `done` is asserted.

### Result Read Timing

```
done asserted → wait ≥ 2 cycles → assert resultReadEnable + resultReadAddr
→ wait 1 cycle → resultReadData valid
```

---

## 6. Known Limitations

### One Activation Vector per Pass

Each computation pass processes one activation vector. A full matrix-matrix multiplication
requires one pass per row of the activation matrix. The weight matrix remains in the PEs
between passes when `loadWeights=false`, so only the activations need to be reloaded.

### Accumulator Does Not Persist Across Cycles

The PE accumulator is overwritten on each `enable` cycle:
```
accumulator := weight × activation + partialSumIn
```
It does **not** add to the existing accumulator value. If temporal accumulation within a
single PE were needed (e.g., streaming multiple input channels through the same PE on
successive cycles without a partial-sum bus), the accumulator update would need to become:
```
accumulator := accumulator + weight × activation
```
The current design accumulates across the spatial row dimension via the vertical partial-sum
flow, not temporally.

### Accumulator Overflow (when `saturate=false`)

When saturation is disabled (the default), the accumulator wraps around silently on overflow.
The `2*dataWidth + 16` bit width (32 bits for `dataWidth=8`) is large enough for typical
workloads. Enable `saturate=true` to clamp results to `[Int.MinValue, Int.MaxValue]` instead.
Saturation is a Chisel-level elaboration parameter; it cannot be changed at runtime.

### Synchronous Memory — One-Cycle Read Latency

All buffers (`SyncReadMem`) have a one-cycle read latency. The address mapping logic in
`Top.scala` compensates with `RegNext` delays. Attempting to read and use data in the same
cycle the read is issued will produce stale data.

### Result Buffer Entry Counter

The `Buffer` module tracks entry count with a simple up/down counter. It does not correctly
account for overwrites (writing the same address twice increments the counter twice). The
`full` and `empty` status signals are therefore only reliable when the buffer is used as a
simple direct-addressed SRAM with non-overlapping writes, as in this design.

### AXI4-Lite Write Channel Handshake

The write FSM requires both AW and W channels to be valid in the same clock cycle. Masters
that drive AW and W independently (presenting one before the other) will stall until both are
asserted simultaneously. This is compliant with the AXI4-Lite specification, which permits
either ordering, but requires that the slave be capable of accepting them in any order. A
future revision could buffer one channel while waiting for the other.

---

## 7. Build & Test

### Prerequisites

- **Java** JDK 11 or higher
- **Scala** 2.13.12
- **sbt** (Scala Build Tool) 1.9.x
- **Chisel** 6.0.0 (resolved automatically by sbt)

### Compile the Project

```bash
sbt compile
# or
make compile
```

### Run Tests

```bash
# All tests
sbt test

# Single test suite
sbt "testOnly accelerator.TopSpec"
sbt "testOnly accelerator.TopAXISpec"
sbt "testOnly accelerator.ArraySpec"
sbt "testOnly accelerator.PESpec"
sbt "testOnly accelerator.BufferSpec"
sbt "testOnly accelerator.ControllerSpec"

# Via Makefile
make test-module MODULE=PE
make test-module MODULE=TopAXI
```

### Generate Verilog

The preferred entry points live in `EmitVerilog.scala` and emit a top-level netlist for
each safety variant:

```bash
sbt "runMain accelerator.EmitVerilog"           # baseline Top (NoSafety)
sbt "runMain accelerator.EmitVerilogNoSafety"   # Top with explicit NoSafety
sbt "runMain accelerator.EmitVerilogParity"     # Top with Parity-protected PEs
sbt "runMain accelerator.EmitVerilogTMR"        # Top with TMR-protected PEs
```

For evaluating the safety PEs in isolation:

```bash
sbt "runMain accelerator.safety.EmitParityPE"   # ParityPE on its own
sbt "runMain accelerator.safety.EmitTMRPE"      # TMRPE on its own
```

Each command writes to `generated/` (or a variant subdirectory such as
`generated/parity/`). The legacy `accelerator.TopMain` / `PEMain` / `ArrayMain` entry
points in `Main.scala` still work but predate the safety subsystem.

### Test Coverage

| Test Suite | What is Tested |
|---|---|
| `PESpec` | Weight loading, MAC operation, activation forwarding, positive overflow saturation, negative overflow saturation, non-saturating values with `saturate=true` |
| `ArraySpec` | Weight loading broadcast, systolic activation flow |
| `BufferSpec` | Read/write, full/empty status |
| `ControllerSpec` | FSM state transitions, output signal timing |
| `TopSpec` | Vector × matrix (2×2, 4×4), matrix × matrix (2×2, 4×4), accumulator reset across passes, tile accumulation across two activation tiles |
| `TopAXISpec` | Idle/reset state, weight and activation load via AXI, computation start and status polling, result read via AXI |
| `TopSafetySpec` | End-to-end equivalence of `NoSafety` / `Parity` / `TMR` Top variants on a fault-free workload |
| `FaultInjectionSpec` | Baseline PE silently propagates a stuck-at fault; `ParityPE` raises `errorDetected` on injected accumulator corruption and stays clean on normal runs; `TMRPE` masks a single-replica fault, votes the correct result, and raises `errorDetected` |
| `SyncReadMemTest` | Chisel SyncReadMem read-after-write behaviour |
