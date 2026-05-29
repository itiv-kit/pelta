package accelerator

import chisel3._
import chisel3.util._

/**
 * Processing Element (PE) for systolic array
 * Implements a multiply-accumulate (MAC) unit for weight stationary dataflow
 * 
 * @param dataWidth Width of input data in bits
 */
class PE(dataWidth: Int = 8, saturate: Boolean = false) extends Module {
  val io = IO(new Bundle {
    // Data inputs
    val weightIn = Input(SInt(dataWidth.W))
    val activationIn = Input(SInt(dataWidth.W))
    
    // Data outputs (pass-through for systolic flow)
    val activationOut = Output(SInt(dataWidth.W))
    
    // Partial sum interface
    val partialSumIn = Input(SInt((2 * dataWidth + 16).W))
    val partialSumOut = Output(SInt((2 * dataWidth + 16).W))
    
    // Control signals
    val loadWeight = Input(Bool())
    val enable = Input(Bool())
    val clearAccum = Input(Bool())
  })

  // Internal weight register (weight stationary)
  val weightReg = RegInit(0.S((dataWidth).W))
  
  // Internal accumulator
  val accumulator = RegInit(0.S((2 * dataWidth + 16).W))

  // Load weight when commanded
  when(io.loadWeight) {
    weightReg := io.weightIn
  }

  // clearAccum takes priority: reset accumulator before a new pass
  when(io.clearAccum) {
    accumulator := 0.S
  }.elsewhen(io.enable) {
    val accWidth = 2 * dataWidth + 16
    val product = weightReg * io.activationIn
    val rawSum = product +& io.partialSumIn  // one bit wider for overflow detection
    if (saturate) {
      val maxVal = ((BigInt(1) << (accWidth - 1)) - 1).S(accWidth.W)
      val minVal = (-(BigInt(1) << (accWidth - 1))).S(accWidth.W)
      // overflow iff the extra carry bit and the result sign bit differ
      val overflow = rawSum(accWidth) =/= rawSum(accWidth - 1)
      // extra bit = 1 means true result underflowed below minVal
      val underflowed = rawSum(accWidth).asBool
      accumulator := Mux(overflow, Mux(underflowed, minVal, maxVal), rawSum(accWidth - 1, 0).asSInt)
    } else {
      accumulator := rawSum(accWidth - 1, 0).asSInt
    }
  }

  // Pass activation through for systolic flow
  io.activationOut := RegNext(io.activationIn)
  
  // Output accumulated result
  io.partialSumOut := accumulator
}
