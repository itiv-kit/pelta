package accelerator

import chisel3._
import chisel3.util._

/**
 * Controller for the systolic array accelerator
 * Manages data flow and operation sequencing
 * 
 * @param rows Number of rows in the array
 * @param cols Number of columns in the array
 */
class Controller(rows: Int = 4, cols: Int = 4) extends Module {
  val io = IO(new Bundle {
    // Command interface
    val start = Input(Bool())
    val loadWeights = Input(Bool())
    
    // Status
    val busy = Output(Bool())
    val done = Output(Bool())
    
    // Control outputs to array
    val arrayLoadWeights = Output(Bool())
    val arrayEnable = Output(Bool())
    val arrayClearAccum = Output(Bool())
    
    // Buffer control
    val readActivation = Output(Bool())
    val writeResult = Output(Bool())
  })

  // Compute cycles
  val computeCycles = (rows + cols).U

  // State machine
  val sIdle :: sLoadWeights :: sCompute :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  // Load counter
  val loadCounter = RegInit(0.U(16.W))
  val loadDone = loadCounter === (rows.U * cols.U + 1.U)  

  // Cycle counter for compute phase
  val cycleCounter = RegInit(0.U(16.W))
  val startWrite = cycleCounter >= computeCycles - cols.U + 1.U

  val computeDone = cycleCounter >= computeCycles
 
  // Default outputs
  io.busy := state =/= sIdle
  io.done := state === sDone
  io.arrayLoadWeights := false.B
  io.arrayEnable := false.B
  io.arrayClearAccum := false.B
  io.readActivation := false.B
  io.writeResult := false.B

  // State machine logic
  switch(state) {
    is(sIdle) {
      cycleCounter := 0.U
      io.writeResult := false.B
      when(io.start && io.loadWeights) {
        io.arrayClearAccum := true.B
        state := sLoadWeights
      }.elsewhen(io.start) {
        io.arrayClearAccum := true.B
        state := sCompute
      }
    }
    
    is(sLoadWeights) {
      io.arrayLoadWeights := true.B
      when(loadDone) {
        loadCounter := 0.U
        state := sCompute
      }.otherwise {
        loadCounter := loadCounter + 1.U
      }
    }
    
    is(sCompute) {
      io.arrayEnable := true.B
      io.readActivation := true.B
      cycleCounter := cycleCounter + 1.U
      when(startWrite) {
        io.writeResult := true.B
      }
      when(computeDone) {
        state := sDone
      }
    }
    
    is(sDone) {
      io.writeResult := true.B
      when(!io.start) {
        state := sIdle
      }
    }
  }
}
