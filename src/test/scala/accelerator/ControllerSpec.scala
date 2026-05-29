package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class ControllerSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Controller"

  it should "start in idle state" in {
    test(new Controller(4, 4)) { dut =>
      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
    }
  }

  it should "transition to load weights state" in {
    test(new Controller(4, 4)) { dut =>
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(true.B)
      dut.clock.step()
      
      dut.io.busy.expect(true.B)
      dut.io.arrayLoadWeights.expect(true.B)
    }
  }

  it should "transition to compute state" in {
    test(new Controller(4, 4)) { dut =>
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      // dut.io.computeCycles.poke(5.U)
      dut.clock.step()
      
      dut.io.busy.expect(true.B)
      dut.io.arrayEnable.expect(true.B)
      dut.io.readActivation.expect(true.B)
    }
  }

  it should "complete computation after specified cycles" in {
    test(new Controller(4, 4)) { dut =>
      // computeCycles = rows + cols + 2 = 10 for a 4x4 array
      // cycleCounter counts 0..9, done fires when counter reaches 10
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      dut.clock.step()   // IDLE → COMPUTE

      dut.clock.step(11)  // 11 compute cycles until cycleCounter >= 10
      dut.io.done.expect(true.B)
      dut.io.writeResult.expect(true.B)
    }
  }

  it should "return to idle after done" in {
    test(new Controller(4, 4)) { dut =>
      // computeCycles = rows + cols + 2 = 10 for a 4x4 array
      dut.io.start.poke(true.B)
      dut.io.loadWeights.poke(false.B)
      dut.clock.step(12)  // 1 to enter COMPUTE + 11 compute cycles → DONE

      dut.io.start.poke(false.B)
      dut.clock.step()    // DONE → IDLE

      dut.io.busy.expect(false.B)
      dut.io.done.expect(false.B)
    }
  }
}
