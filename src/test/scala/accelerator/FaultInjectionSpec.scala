package accelerator

import chisel3._
import chisel3.util.MuxLookup
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import accelerator.safety.{ParityPE, TMRPE}

// ---------------------------------------------------------------------------
// Test-only harnesses — not part of the production design
// ---------------------------------------------------------------------------

/** Wraps a plain PE with a fault-injection mux on the acc output.
 *  faultMask bits are XOR-ed into partialSumOut when faultEnable is high.
 *  No errorDetected port — baseline has no safety mechanism.
 */
class FaultableBasePE(dataWidth: Int = 8) extends Module {
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
    val faultEnable   = Input(Bool())
    val faultMask     = Input(UInt(accWidth.W))
  })
  val pe = Module(new PE(dataWidth))
  pe.io.weightIn     := io.weightIn
  pe.io.activationIn := io.activationIn
  pe.io.partialSumIn := io.partialSumIn
  pe.io.loadWeight   := io.loadWeight
  pe.io.enable       := io.enable
  pe.io.clearAccum   := io.clearAccum
  io.activationOut   := pe.io.activationOut
  val accObserved = Mux(io.faultEnable,
    (pe.io.partialSumOut.asUInt ^ io.faultMask).asSInt,
    pe.io.partialSumOut)
  io.partialSumOut := accObserved
}

/** Mirrors ParityPE's encoded-register datapath and adds a fault-injection mux
 *  on the observed accumulator value. faultMask XORed into accObserved at
 *  read time simulates a flipped storage bit while parityReg keeps the
 *  pre-fault parity, so the mismatch persists for as long as the fault is held.
 */
class FaultableParityPE(dataWidth: Int = 8) extends Module {
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
    val faultEnable   = Input(Bool())
    val faultMask     = Input(UInt(accWidth.W))
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

  val accObserved = Mux(io.faultEnable,
    (accumulator.asUInt ^ io.faultMask).asSInt,
    accumulator)
  io.partialSumOut := accObserved

  io.errorDetected := parityReg =/= accObserved.asUInt.xorR
}

/** Three PEs with a majority voter plus a fault mux on pe0's acc output. */
class FaultableTMRPE(dataWidth: Int = 8) extends Module {
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
    val faultEnable   = Input(Bool())
    val faultValue    = Input(SInt(accWidth.W))
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

  val a0 = Mux(io.faultEnable, io.faultValue, pe0.io.partialSumOut)
  val a1 = pe1.io.partialSumOut
  val a2 = pe2.io.partialSumOut
  val voted = ((a0.asUInt & a1.asUInt) | (a1.asUInt & a2.asUInt) | (a0.asUInt & a2.asUInt)).asSInt
  io.partialSumOut  := voted
  io.errorDetected  := (voted =/= a0) || (voted =/= a1) || (voted =/= a2)
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class FaultInjectionSpec extends AnyFlatSpec with ChiselScalatestTester {

  // Shared helpers
  private def loadWeight(dut: { val io: { val loadWeight: Bool; val weightIn: SInt } },
                         w: Int): Unit = {
    dut.io.loadWeight.poke(true.B)
    dut.io.weightIn.poke(w.S)
    dut.io.loadWeight.poke(false.B)
  }

  // -------------------------------------------------------------------------
  // Baseline PE — fault injection reference
  // -------------------------------------------------------------------------

  behavior of "Baseline PE (no safety)"

  it should "pass corrupted acc through silently (LSB stuck-at)" in {
    test(new FaultableBasePE(8)) { dut =>
      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      // One MAC: 3 * 5 = 15
      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(5.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      dut.io.enable.poke(false.B)

      // Settle: acc is now 15, parityReg catches up
      dut.io.faultEnable.poke(false.B)
      dut.clock.step()
      dut.io.partialSumOut.expect(15.S)

      // Inject fault: flip LSB → 15 becomes 14
      dut.io.faultEnable.poke(true.B)
      dut.io.faultMask.poke(1.U)
      dut.clock.step()

      // Corrupted value passes through — baseline has no detection
      dut.io.partialSumOut.expect(14.S)
      // (No errorDetected port on FaultableBasePE by design)
    }
  }

  // -------------------------------------------------------------------------
  // ParityPE
  // -------------------------------------------------------------------------

  behavior of "ParityPE"

  it should "keep errorDetected false after 10 normal MAC cycles" in {
    test(new ParityPE(8)) { dut =>
      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      // 10 MAC cycles with activations 1..10
      dut.io.enable.poke(true.B)
      dut.io.partialSumIn.poke(0.S)
      for (a <- 1 to 10) {
        dut.io.activationIn.poke(a.S)
        dut.clock.step()
      }
      dut.io.enable.poke(false.B)

      // One idle cycle so parityReg catches up to the stable acc
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)
    }
  }

  it should "fault-free run produces no false positives" in {
    test(new FaultableParityPE(8)) { dut =>
      dut.io.faultEnable.poke(false.B)
      dut.io.faultMask.poke(0.U)
      dut.io.clearAccum.poke(false.B)
      dut.io.partialSumIn.poke(0.S)
      dut.io.enable.poke(false.B)
      dut.io.errorDetected.expect(false.B)

      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)
      dut.io.loadWeight.poke(false.B)

      // 10 MAC cycles with activations 1..10 — check on every cycle
      dut.io.enable.poke(true.B)
      for (a <- 1 to 10) {
        dut.io.activationIn.poke(a.S)
        dut.clock.step()
        dut.io.errorDetected.expect(false.B)
      }
      dut.io.enable.poke(false.B)

      // Trailing idle cycle
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)
    }
  }

  it should "assert errorDetected on a simulated LSB stuck-at fault" in {
    test(new FaultableParityPE(8)) { dut =>
      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      // One MAC: acc becomes 15
      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(5.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.faultEnable.poke(false.B)

      // Settle: parityReg = parity(15)
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)

      // Inject fault combinationally: flip LSB of acc (15 → 14).
      // parityReg holds parity(15); parityActual = parity(14). The mismatch
      // is visible immediately — no clock edge needed.
      dut.io.faultEnable.poke(true.B)
      dut.io.faultMask.poke(1.U)
      dut.io.errorDetected.expect(true.B)

      // The fault must STAY asserted across cycles while held — proves the
      // detector is not a one-shot that latches the corrupted parity and
      // goes silent on the next cycle.
      for (_ <- 0 until 5) {
        dut.clock.step()
        dut.io.errorDetected.expect(true.B)
      }
    }
  }

  // -------------------------------------------------------------------------
  // TMRPE
  // -------------------------------------------------------------------------

  behavior of "TMRPE"

  it should "keep errorDetected false when all three PEs agree" in {
    test(new TMRPE(8)) { dut =>
      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      // Run 5 MAC cycles; all three replicas compute identically
      dut.io.enable.poke(true.B)
      dut.io.partialSumIn.poke(0.S)
      for (a <- 1 to 5) {
        dut.io.activationIn.poke(a.S)
        dut.clock.step()
        dut.io.errorDetected.expect(false.B)
      }
    }
  }

  it should "fault-free run produces no false positives" in {
    test(new FaultableTMRPE(8)) { dut =>
      dut.io.faultEnable.poke(false.B)
      dut.io.faultValue.poke(0.S)
      dut.io.clearAccum.poke(false.B)
      dut.io.partialSumIn.poke(0.S)
      dut.io.enable.poke(false.B)
      dut.io.errorDetected.expect(false.B)

      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)
      dut.io.loadWeight.poke(false.B)

      // 10 MAC cycles with activations 1..10
      dut.io.enable.poke(true.B)
      for (a <- 1 to 10) {
        dut.io.activationIn.poke(a.S)
        dut.clock.step()
        dut.io.errorDetected.expect(false.B)
      }
      dut.io.enable.poke(false.B)

      // Trailing idle cycle
      dut.clock.step()
      dut.io.errorDetected.expect(false.B)
    }
  }

  it should "detect and correct a single-PE fault, voting result matches healthy PEs" in {
    test(new FaultableTMRPE(8)) { dut =>
      // Load weight = 3
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      // One MAC: 3 * 5 = 15, all PEs accumulate to 15
      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(5.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      dut.io.enable.poke(false.B)
      dut.io.faultEnable.poke(false.B)

      // Confirm fault-free state: acc = 15, no error
      dut.clock.step()
      dut.io.partialSumOut.expect(15.S)
      dut.io.errorDetected.expect(false.B)

      // Override pe0's output to 0xDEADBEEF while pe1 and pe2 still output 15
      dut.io.faultEnable.poke(true.B)
      dut.io.faultValue.poke(0xDEADBEEFL.toInt.S)
      dut.clock.step()

      // Majority voter corrects: 15 wins 2-to-1 over 0xDEADBEEF
      dut.io.partialSumOut.expect(15.S)
      // Disagreement between pe0 and the voter is flagged
      dut.io.errorDetected.expect(true.B)
    }
  }
}
