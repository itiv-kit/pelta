package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class PESpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "PE"

  it should "load weights correctly" in {
    test(new PE(8)) { dut =>
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(5.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)
      
      // Weight should be loaded
      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(2.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      
      // Result should be 5 * 2 = 10
      dut.io.partialSumOut.expect(10.S)
    }
  }

  it should "perform MAC operation correctly" in {
    test(new PE(8)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Load weight
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)
      
      // First MAC: 3 * 4 + 0 = 12
      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(4.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      dut.io.partialSumOut.expect(12.S)
      
      // Second MAC: 3 * 2 + 5 = 11
      dut.io.activationIn.poke(2.S)
      dut.io.partialSumIn.poke(5.S)
      dut.clock.step()
      dut.io.partialSumOut.expect(11.S)
    }
  }

  it should "pass activations through" in {
    test(new PE(8)) { dut =>
      dut.io.activationIn.poke(7.S)
      dut.clock.step()
      dut.io.activationOut.expect(7.S)
    }
  }

  it should "saturate accumulator on positive overflow" in {
    // accWidth = 2*8+16 = 32; maxVal = 2147483647
    // weight=127, activation=127 → product=16129
    // partialSumIn = Int.MaxValue - 1 = 2147483646 → sum = 2147483646 + 16129 > maxVal → clamp to maxVal
    test(new PE(8, saturate = true)) { dut =>
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(127.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(127.S)
      dut.io.partialSumIn.poke(2147483646.S)
      dut.clock.step()
      dut.io.partialSumOut.expect(2147483647.S)
    }
  }

  it should "saturate accumulator on negative overflow" in {
    // weight=-128, activation=127 → product=-16256
    // partialSumIn = Int.MinValue + 1 = -2147483647 → sum = -2147483647 + (-16256) < minVal → clamp to minVal
    test(new PE(8, saturate = true)) { dut =>
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke((-128).S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(127.S)
      dut.io.partialSumIn.poke((-2147483647L).S)
      dut.clock.step()
      dut.io.partialSumOut.expect((-2147483648L).S)
    }
  }

  it should "not saturate non-overflowing values when saturate=true" in {
    test(new PE(8, saturate = true)) { dut =>
      dut.io.loadWeight.poke(true.B)
      dut.io.weightIn.poke(3.S)
      dut.clock.step()
      dut.io.loadWeight.poke(false.B)

      dut.io.enable.poke(true.B)
      dut.io.activationIn.poke(4.S)
      dut.io.partialSumIn.poke(0.S)
      dut.clock.step()
      dut.io.partialSumOut.expect(12.S)
    }
  }
}
