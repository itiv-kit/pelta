package accelerator.safety

import chisel3._

/** Encoded-register fault detector for a PE accumulator.
 *
 *  Replicates the baseline PE datapath (PE.scala, non-saturating mode) with
 *  an additional parityReg that is written from the same source as the
 *  accumulator on the same clock edge. A bit-flip in the accumulator
 *  register between write and read makes parity(accumulator) disagree with
 *  parityReg → errorDetected fires and stays asserted until the fault clears.
 *
 *  PE.scala is intentionally not reused: the parity bit must shadow the
 *  accumulator register itself, not just its output port, so the detector
 *  can see corruption inside the storage cell.
 */
class ParityPE(dataWidth: Int = 8) extends Module {
  val accWidth = 2 * dataWidth + 16

  val io = IO(new Bundle {
    val weightIn      = Input(SInt(dataWidth.W))
    val activationIn  = Input(SInt(dataWidth.W))
    val activationOut = Output(SInt(dataWidth.W))
    val partialSumIn  = Input(SInt(accWidth.W))
    val partialSumOut = Output(SInt(accWidth.W))
    val loadWeight    = Input(Bool())
    val enable        = Input(Bool())
    val clearAccum    = Input(Bool())
    val errorDetected = Output(Bool())
  })

  val weightReg   = RegInit(0.S(dataWidth.W))
  val accumulator = RegInit(0.S(accWidth.W))
  val parityReg   = RegInit(false.B)

  when(io.loadWeight) {
    weightReg := io.weightIn
  }

  when(io.clearAccum) {
    accumulator := 0.S
    parityReg   := false.B
  }.elsewhen(io.enable) {
    val product = weightReg * io.activationIn
    val rawSum  = product +& io.partialSumIn
    val nextAcc = rawSum(accWidth - 1, 0).asSInt
    accumulator := nextAcc
    parityReg   := nextAcc.asUInt.xorR
  }

  io.activationOut := RegNext(io.activationIn)
  io.partialSumOut := accumulator

  io.errorDetected := parityReg =/= accumulator.asUInt.xorR
}
