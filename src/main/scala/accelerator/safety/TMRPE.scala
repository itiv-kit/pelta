package accelerator.safety

import chisel3._
import accelerator.PE

class TMRPE(dataWidth: Int = 8) extends Module {
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

  val pe0 = Module(new PE(dataWidth))
  val pe1 = Module(new PE(dataWidth))
  val pe2 = Module(new PE(dataWidth))

  for (pe <- Seq(pe0, pe1, pe2)) {
    pe.io.weightIn     := io.weightIn
    pe.io.activationIn := io.activationIn
    pe.io.partialSumIn := io.partialSumIn
    pe.io.loadWeight   := io.loadWeight
    pe.io.enable       := io.enable
    pe.io.clearAccum   := io.clearAccum
  }
  io.activationOut := pe0.io.activationOut

  // Bitwise majority voter: result(i) = majority(pe0(i), pe1(i), pe2(i))
  val u0 = pe0.io.partialSumOut.asUInt
  val u1 = pe1.io.partialSumOut.asUInt
  val u2 = pe2.io.partialSumOut.asUInt
  val voted = ((u0 & u1) | (u1 & u2) | (u0 & u2)).asSInt
  io.partialSumOut := voted

  // Any two PE outputs disagreeing means at least one replica diverged.
  io.errorDetected := (voted =/= pe0.io.partialSumOut) ||
                      (voted =/= pe1.io.partialSumOut) ||
                      (voted =/= pe2.io.partialSumOut)
}
