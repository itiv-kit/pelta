package accelerator

import chisel3._
import accelerator.safety.{ParityPE, TMRPE}

/** Thin wrapper that dispatches PE construction on a SafetyMode and exposes
 *  a uniform I/O (baseline PE bundle + errorDetected). The Array constructs
 *  a grid of SafePEs, so one code path handles all three safety variants.
 *
 *  Note: ParityPE and TMRPE do not currently expose a `saturate` parameter,
 *  so the wrapper's `saturate` arg only affects the NoSafety path.
 */
class SafePE(dataWidth: Int = 8, saturate: Boolean = false, safety: SafetyMode = NoSafety) extends Module {
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

  safety match {
    case NoSafety =>
      val pe = Module(new PE(dataWidth, saturate))
      pe.io.weightIn     := io.weightIn
      pe.io.activationIn := io.activationIn
      pe.io.partialSumIn := io.partialSumIn
      pe.io.loadWeight   := io.loadWeight
      pe.io.enable       := io.enable
      pe.io.clearAccum   := io.clearAccum
      io.activationOut   := pe.io.activationOut
      io.partialSumOut   := pe.io.partialSumOut
      io.errorDetected   := false.B

    case Parity =>
      val pe = Module(new ParityPE(dataWidth))
      pe.io.weightIn     := io.weightIn
      pe.io.activationIn := io.activationIn
      pe.io.partialSumIn := io.partialSumIn
      pe.io.loadWeight   := io.loadWeight
      pe.io.enable       := io.enable
      pe.io.clearAccum   := io.clearAccum
      io.activationOut   := pe.io.activationOut
      io.partialSumOut   := pe.io.partialSumOut
      io.errorDetected   := pe.io.errorDetected

    case TMR =>
      val pe = Module(new TMRPE(dataWidth))
      pe.io.weightIn     := io.weightIn
      pe.io.activationIn := io.activationIn
      pe.io.partialSumIn := io.partialSumIn
      pe.io.loadWeight   := io.loadWeight
      pe.io.enable       := io.enable
      pe.io.clearAccum   := io.clearAccum
      io.activationOut   := pe.io.activationOut
      io.partialSumOut   := pe.io.partialSumOut
      io.errorDetected   := pe.io.errorDetected
  }
}
