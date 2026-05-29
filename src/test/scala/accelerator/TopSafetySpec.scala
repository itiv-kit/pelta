package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TopSafetySpec extends AnyFlatSpec with ChiselScalatestTester {

  // Reuses the 4x4 matrix x matrix scenario from TopSpec, parameterised over
  // SafetyMode. The driver is a near-verbatim copy of the existing matmul
  // driver but instruments every clock.step() so it can report whether
  // io.errorDetected ever fired during the fault-free run.
  private def driveMatmul(
      dut: Top,
      matrixA: Seq[Seq[Int]],
      matrixB: Seq[Seq[Int]]
  ): (Seq[Seq[BigInt]], Boolean) = {
    val rowsA = matrixA.length
    val colsB = matrixB(0).length
    var errorEverFired = false

    def stepAndCheck(): Unit = {
      dut.clock.step()
      if (dut.io.errorDetected.peek().litToBoolean) errorEverFired = true
    }

    // Load weight matrix B into the weight buffer
    for (r <- 0 until rowsA; c <- 0 until colsB) {
      val addr = r * colsB + c
      dut.io.weightWriteEnable.poke(true.B)
      dut.io.weightWriteAddr.poke(addr.U)
      dut.io.weightWriteData.poke(matrixB(r)(c).U)
      stepAndCheck()
    }
    dut.io.weightWriteEnable.poke(false.B)
    stepAndCheck()

    val results = scala.collection.mutable.ArrayBuffer.empty[Seq[BigInt]]

    for (rowIdx <- 0 until rowsA) {
      // Load activations for this row of A
      for (i <- 0 until rowsA) {
        dut.io.activationWriteEnable.poke(true.B)
        dut.io.activationWriteAddr.poke(i.U)
        dut.io.activationWriteData.poke(matrixA(rowIdx)(i).U)
        stepAndCheck()
      }
      dut.io.activationWriteEnable.poke(false.B)
      stepAndCheck()
      stepAndCheck()

      // Kick off the compute (loadWeights only on the first pass)
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(if (rowIdx == 0) true.B else false.B)
      stepAndCheck()
      dut.io.start.poke(false.B)

      // Wait for done
      var cycles = 0
      while (!dut.io.done.peek().litToBoolean && cycles < 30) {
        stepAndCheck()
        cycles += 1
      }
      assert(dut.io.done.peek().litToBoolean, s"pass $rowIdx never asserted done")

      // Two settle cycles before reading the result buffer (matches TopSpec)
      stepAndCheck()
      stepAndCheck()

      val rowResults = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      for (c <- 0 until colsB) {
        dut.io.resultReadEnable.poke(true.B)
        dut.io.resultReadAddr.poke(c.U)
        stepAndCheck()
        rowResults += dut.io.resultReadData.peek().litValue
      }
      dut.io.resultReadEnable.poke(false.B)
      results += rowResults.toSeq

      // Quiet window before the next pass
      stepAndCheck()
      stepAndCheck()
      stepAndCheck()
    }

    (results.toSeq, errorEverFired)
  }

  behavior of "Top with SafetyMode integration"

  it should "produce identical results in NoSafety, Parity, and TMR modes" in {
    // Same matrices as TopSpec "perform 4x4 matrix x matrix multiplication"
    val matrixA = Seq(
      Seq(1, 2, 1, 0),
      Seq(3, 1, 0, 1),
      Seq(0, 2, 1, 3),
      Seq(1, 0, 2, 1)
    )
    val matrixB = Seq(
      Seq(2, 3, 4, 5),
      Seq(1, 2, 3, 4),
      Seq(5, 5, 5, 5),
      Seq(1, 1, 1, 1)
    )
    val expected = Seq(
      Seq(BigInt(9),  BigInt(12), BigInt(15), BigInt(18)),
      Seq(BigInt(8),  BigInt(12), BigInt(16), BigInt(20)),
      Seq(BigInt(10), BigInt(12), BigInt(14), BigInt(16)),
      Seq(BigInt(13), BigInt(14), BigInt(15), BigInt(16))
    )

    var noSafetyResult: Seq[Seq[BigInt]] = Seq.empty
    var parityResult:   Seq[Seq[BigInt]] = Seq.empty
    var tmrResult:      Seq[Seq[BigInt]] = Seq.empty
    var noSafetyErr = false
    var parityErr   = false
    var tmrErr      = false

    test(new Top(4, 4, 8, 256, safety = NoSafety)) { dut =>
      val (r, e) = driveMatmul(dut, matrixA, matrixB)
      noSafetyResult = r
      noSafetyErr = e
    }
    test(new Top(4, 4, 8, 256, safety = Parity)) { dut =>
      val (r, e) = driveMatmul(dut, matrixA, matrixB)
      parityResult = r
      parityErr = e
    }
    test(new Top(4, 4, 8, 256, safety = TMR)) { dut =>
      val (r, e) = driveMatmul(dut, matrixA, matrixB)
      tmrResult = r
      tmrErr = e
    }

    assert(noSafetyResult == expected, s"NoSafety result mismatch: got $noSafetyResult")
    assert(parityResult   == noSafetyResult, s"Parity result differs from NoSafety: $parityResult vs $noSafetyResult")
    assert(tmrResult      == noSafetyResult, s"TMR result differs from NoSafety: $tmrResult vs $noSafetyResult")

    assert(!noSafetyErr, "NoSafety errorDetected fired during fault-free run")
    assert(!parityErr,   "Parity errorDetected fired during fault-free run")
    assert(!tmrErr,      "TMR errorDetected fired during fault-free run")
  }
}
