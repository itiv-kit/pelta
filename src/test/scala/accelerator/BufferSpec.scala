package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class BufferSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Buffer"

  it should "start empty" in {
    test(new Buffer(16, 32)) { dut =>
      dut.io.empty.expect(true.B)
      dut.io.full.expect(false.B)
    }
  }

  it should "write and read data correctly" in {
    test(new Buffer(8, 32)).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      // Write data
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(5.U)
      dut.io.writeData.poke(42.U)
      dut.clock.step()  
      dut.io.writeEnable.poke(false.B)
      
      // Read data
      dut.io.readEnable.poke(true.B)
      dut.io.readAddr.poke(5.U)
      dut.clock.step() 
      println(s"ReadData = ${dut.io.readData.peek().litValue}")
      dut.io.readData.expect(42.U)
    }
  }

  it should "track entry count correctly" in {
    test(new Buffer(4, 32)) { dut =>
      // Write multiple entries
      for (i <- 0 until 3) {
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(i.U)
        dut.io.writeData.poke((i * 10).U)
        dut.clock.step()
      }
      dut.io.writeEnable.poke(false.B)
      dut.io.empty.expect(false.B)
      
      // Read entries
      for (i <- 0 until 3) {
        dut.io.readEnable.poke(true.B)
        dut.io.readAddr.poke(i.U)
        dut.clock.step()
      }
      dut.io.readEnable.poke(false.B)
    }
  }

  it should "handle full condition" in {
    test(new Buffer(2, 32)) { dut =>
      // Fill buffer
      for (i <- 0 until 2) {
        dut.io.writeEnable.poke(true.B)
        dut.io.writeAddr.poke(i.U)
        dut.io.writeData.poke(i.U)
        dut.clock.step()
      }
      dut.io.full.expect(true.B)
    }
  }
}
