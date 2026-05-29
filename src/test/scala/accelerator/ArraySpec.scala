package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ArraySpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Array"

  it should "instantiate correctly" in {
    test(new Array(2, 2, 8)) { dut =>
      dut.io.enable.poke(false.B)
      dut.clock.step()
    }
  }

  it should "load weights into all PEs" in {
    test(new Array(2, 2, 8)) { dut =>
      // Create weight matrix
      val weights = Seq(
        Seq(1.S, 2.S),
        Seq(3.S, 4.S)
      )
      
      // Load weights
      dut.io.loadWeights.poke(true.B)
      for (r <- 0 until 2) {
        for (c <- 0 until 2) {
          dut.io.weightData(r)(c).poke(weights(r)(c))
        }
      }
      dut.clock.step()
      dut.io.loadWeights.poke(false.B)
      dut.clock.step()
    }
  }

  it should "process activations through systolic flow" in {
    test(new Array(2, 2, 8)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Load weights
      dut.io.loadWeights.poke(true.B)
      for (r <- 0 until 2) {
        for (c <- 0 until 2) {
          dut.io.weightData(r)(c).poke((r * 2 + c + 1).S)
        }
      }
      dut.clock.step()
      dut.io.loadWeights.poke(false.B)
      
      // Enable computation with horizontal activation flow
      dut.io.enable.poke(true.B)
      // Activations enter from left (one per column)
      dut.io.activations(0).poke(1.S)
      dut.io.activations(1).poke(2.S)
      // Partial sums enter from top (one per column)
      dut.io.partialSumsIn(0).poke(0.S)
      dut.io.partialSumsIn(1).poke(0.S)
      
      dut.clock.step(3)
      
      // Partial sums should emerge from bottom
      println(s"PartialSumOut[0] = ${dut.io.partialSumsOut(0).peek().litValue}")
      println(s"PartialSumOut[1] = ${dut.io.partialSumsOut(1).peek().litValue}")
    }
  }
}
