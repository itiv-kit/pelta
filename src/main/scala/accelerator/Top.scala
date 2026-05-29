package accelerator

import chisel3._
import chisel3.util._

/**
 * Top-level module for minimal systolic array accelerator
 * Integrates Array, Controller, and Buffers
 * 
 * @param rows Number of rows in array
 * @param cols Number of columns in array
 * @param dataWidth Width of data path
 * @param bufferDepth Depth of input/output buffers
 */
class Top(
  rows: Int = 4,
  cols: Int = 4,
  dataWidth: Int = 8,
  bufferDepth: Int = 256,
  saturate: Boolean = false,
  safety: SafetyMode = NoSafety
) extends Module {
  val io = IO(new Bundle {
    // Configuration interface
    val start = Input(Bool())
    val loadWeights = Input(Bool())
    val accumulate = Input(Bool())  // when true, add new results to existing tile accumulator

    // Data interface
    val weightWriteEnable = Input(Bool())
    val weightWriteAddr = Input(UInt(log2Ceil(bufferDepth).W))
    val weightWriteData = Input(UInt(dataWidth.W))

    val activationWriteEnable = Input(Bool())
    val activationWriteAddr = Input(UInt(log2Ceil(bufferDepth).W))
    val activationWriteData = Input(UInt(dataWidth.W))

    val resultReadEnable = Input(Bool())
    val resultReadAddr = Input(UInt(log2Ceil(bufferDepth).W))
    val resultReadData = Output(SInt((2 * dataWidth + 16).W))

    // Status
    val busy = Output(Bool())
    val done = Output(Bool())
    val errorDetected = Output(Bool())
  })

  // Instantiate modules
  val controller = Module(new Controller(rows, cols))
  val array = Module(new Array(rows, cols, dataWidth, saturate, safety))
  io.errorDetected := array.io.errorDetected
  val weightBuffer = Module(new Buffer(bufferDepth, dataWidth))
  val activationBuffer = Module(new Buffer(bufferDepth, dataWidth))
  val resultBuffer = Module(new Buffer(bufferDepth, 2 * dataWidth + 16))

  // Connect controller
  controller.io.start := io.start
  controller.io.loadWeights := io.loadWeights
  io.busy := controller.io.busy
  io.done := controller.io.done

  // Connect array to controller
  array.io.loadWeights := controller.io.arrayLoadWeights
  array.io.enable := controller.io.arrayEnable
  array.io.clearAccum := controller.io.arrayClearAccum

  // Weight buffer connections
  weightBuffer.io.writeEnable := io.weightWriteEnable
  weightBuffer.io.writeAddr := io.weightWriteAddr
  weightBuffer.io.writeData := io.weightWriteData
  // readEnable and readAddr are driven exclusively in when/otherwise blocks below

  // Activation buffer connections
  activationBuffer.io.writeEnable := io.activationWriteEnable
  activationBuffer.io.writeAddr := io.activationWriteAddr
  activationBuffer.io.writeData := io.activationWriteData
  // readEnable and readAddr are driven exclusively in when/otherwise blocks below

  // Result buffer connections
  // resultBuffer.io.writeEnable will be driven by gated logic below
  resultBuffer.io.writeAddr := 0.U
  resultBuffer.io.writeData := 0.U
  resultBuffer.io.readEnable := io.resultReadEnable
  resultBuffer.io.readAddr := io.resultReadAddr
  io.resultReadData := resultBuffer.io.readData.asSInt

  val resultWriteHappening = WireDefault(false.B)
  val resultWriteActive = RegInit(false.B)
  val resultWritePrev = RegNext(controller.io.writeResult, false.B)
  val resultWriteStart = controller.io.writeResult && !resultWritePrev
  val resultCapturePending = RegInit(false.B)
  val resultCaptureDelay = RegInit(0.U(2.W))
  val stagedResultData = Seq.fill(cols)(RegInit(0.U((2 * dataWidth + 16).W)))

  // Tile accumulation registers: hold running partial sums across consecutive tiled passes
  val tileAccumRegs = Seq.fill(cols)(RegInit(0.S((2 * dataWidth + 16).W)))

  when(resultWriteStart) {
    resultCapturePending := true.B
    resultCaptureDelay := (cols - 1).U
  }

  when(resultCapturePending) {
    when(resultCaptureDelay === 0.U) {
      for (c <- 0 until cols) {
        val newVal = Mux(io.accumulate,
          tileAccumRegs(c) + array.io.partialSumsOut(c),
          array.io.partialSumsOut(c))
        tileAccumRegs(c) := newVal
        stagedResultData(c) := newVal.asUInt
      }
      resultWriteActive := true.B
      resultCapturePending := false.B
    }.otherwise {
      resultCaptureDelay := resultCaptureDelay - 1.U
    }
  }

  // Result buffer write logic
  val resultWriteCounter = RegInit(0.U(log2Ceil(bufferDepth).W))
  when(resultWriteActive) {
    resultBuffer.io.writeAddr := resultWriteCounter

    for(c <- 0 until cols) {
      when(resultWriteCounter === c.U) {
        resultWriteHappening := true.B
        resultBuffer.io.writeData := stagedResultData(c)
      }
    }

    when(resultWriteCounter === (cols - 1).U) {
      resultWriteCounter := 0.U
      resultWriteActive := false.B
    }.otherwise {
      resultWriteCounter := resultWriteCounter + 1.U
    }
  }.otherwise {
    resultWriteCounter := 0.U
  }

  dontTouch(resultWriteHappening)

  // Keep writeEnable asserted for the full result flush window.
  resultBuffer.io.writeEnable := resultWriteActive
  
  // Connect array inputs (simplified)
  // Activations enter horizontally (one per row)

  val activationCounter = RegInit(0.U(log2Ceil(bufferDepth).W))
  val activationRegs = Seq.fill(rows)(RegInit(0.S(dataWidth.W)))

  // Drive the array from held activation registers so values stay valid during compute.
  for (r <- 0 until rows) {
    array.io.activations(r) := activationRegs(r)
  }

  val activationReadActive = controller.io.readActivation && activationCounter < rows.U

  when(controller.io.readActivation) {
    when(activationCounter < rows.U) {
      activationBuffer.io.readEnable := true.B
      activationBuffer.io.readAddr := activationCounter
      activationCounter := activationCounter + 1.U
    }.otherwise {
      activationBuffer.io.readEnable := false.B
      activationBuffer.io.readAddr := 0.U
    }
    for (r <- 0 until rows) {
      when(RegNext(activationReadActive) && RegNext(activationBuffer.io.readAddr) === r.U) {
        activationRegs(r) := activationBuffer.io.readData.asSInt
      }
    }
  }.otherwise {
    activationBuffer.io.readEnable := false.B
    activationBuffer.io.readAddr := 0.U
    activationCounter := 0.U
  }
  
  
  // Partial sums enter vertically (one per column)
  for (c <- 0 until cols) {
    array.io.partialSumsIn(c) := 0.S
  }
  
  // Weight storage and loading
  // Store weights in registers, loaded sequentially
  val weightRegs = Seq.fill(rows, cols)(RegInit(0.S(dataWidth.W)))
  val weightLoadCounter = RegInit(0.U(log2Ceil(rows * cols).W))
  val targetRow = WireDefault(0.U(log2Ceil(rows).W))
  val targetCol = WireDefault(0.U(log2Ceil(cols).W))

  // Time-multiplexed weight loading
  when(controller.io.arrayLoadWeights) {
    weightBuffer.io.readEnable := true.B
    weightBuffer.io.readAddr := weightLoadCounter
    // Calculate which PE to update --> RegNext delay
    targetRow := RegNext(weightLoadCounter / cols.U, 0.U)
    targetCol := RegNext(weightLoadCounter % cols.U, 0.U)

    // Update the corresponding weight register
    for (r <- 0 until rows) {
      for (c <- 0 until cols) {
        when(targetRow === r.U && targetCol === c.U) {
          weightRegs(r)(c) := weightBuffer.io.readData.asSInt
        }
      }
    }
    weightLoadCounter := weightLoadCounter + 1.U
  }.otherwise {
    weightBuffer.io.readEnable := false.B
    weightBuffer.io.readAddr := 0.U
    weightLoadCounter := 0.U
  }
  
  // Connect weight registers to array (always driven)
  for (r <- 0 until rows) {
    for (c <- 0 until cols) {
      array.io.weightData(r)(c) := weightRegs(r)(c)
    }
  }
} 