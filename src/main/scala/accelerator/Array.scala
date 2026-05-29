package accelerator

import chisel3._
import chisel3.util._

/**
 * Systolic Array of Processing Elements
 * Implements a 2D weight stationary systolic array
 * Dataflow: Activations flow horizontally (left to right)
 *           Partial sums flow vertically (top to bottom)
 * 
 * @param rows Number of rows in the array
 * @param cols Number of columns in the array
 * @param dataWidth Width of input data in bits
 */
class Array(rows: Int = 4, cols: Int = 4, dataWidth: Int = 8, saturate: Boolean = false, safety: SafetyMode = NoSafety) extends Module {
  val io = IO(new Bundle {
    // Weight loading interface
    val weightData = Input(Vec(rows, Vec(cols, SInt(dataWidth.W))))
    val loadWeights = Input(Bool())

    // Activation inputs (one per row - enter from left)
    val activations = Input(Vec(rows, SInt(dataWidth.W)))

    // Partial sum inputs (one per column - enter from top)
    val partialSumsIn = Input(Vec(cols, SInt((2 * dataWidth + 16).W)))

    // Partial sum outputs (one per column - exit from bottom)
    val partialSumsOut = Output(Vec(cols, SInt((2 * dataWidth + 16).W)))

    // Control
    val enable = Input(Bool())
    val clearAccum = Input(Bool())

    // OR-reduced fault signal across every PE in the array (always false in NoSafety mode)
    val errorDetected = Output(Bool())
  })

  // Create 2D array of PEs (dispatched on SafetyMode via SafePE wrapper)
  val peArray = Seq.fill(rows, cols)(Module(new SafePE(dataWidth, saturate, safety)))

  // Connect PEs in systolic pattern
  for (r <- 0 until rows) {
    for (c <- 0 until cols) {
      val pe = peArray(r)(c)
      
      // Weight loading
      pe.io.weightIn := io.weightData(r)(c)
      pe.io.loadWeight := io.loadWeights
      
      // Activation flow (horizontal - left to right)
      if (c == 0) {
        pe.io.activationIn := io.activations(r)
      } else {
        pe.io.activationIn := peArray(r)(c - 1).io.activationOut
      }
      
      // Partial sum flow (vertical - top to bottom)
      if (r == 0) {
        pe.io.partialSumIn := io.partialSumsIn(c)
      } else {
        pe.io.partialSumIn := peArray(r - 1)(c).io.partialSumOut
      }
      
      // Control
      pe.io.enable := io.enable
      pe.io.clearAccum := io.clearAccum
      
      // Output partial sums from last row
      if (r == rows - 1) {
        io.partialSumsOut(c) := pe.io.partialSumOut
      }
    }
  }

  io.errorDetected := peArray.flatten.map(_.io.errorDetected).reduce(_ || _)
}
