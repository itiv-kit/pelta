package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3.SyncReadMem._

class SimpleSyncReadMemModule extends Module {
  val io = IO(new Bundle {
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(3.W))
    val writeData = Input(UInt(32.W))
    val readAddr = Input(UInt(3.W))
    val readData = Output(UInt(32.W))
  })
  
  val mem = SyncReadMem(8, UInt(32.W), WriteFirst)
  
  when(io.writeEnable) {
    mem.write(io.writeAddr, io.writeData)
  }
  
  io.readData := mem.read(io.readAddr)
}

class SyncReadMemTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SyncReadMem"

  it should "test write and read timing" in {
    test(new SimpleSyncReadMemModule).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("=== Testing SyncReadMem timing ===")
      
      // Test 1: Write for 1 cycle
      println("\n--- Write for 1 cycle ---")
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(5.U)
      dut.io.writeData.poke(42.U)
      dut.io.readAddr.poke(5.U)
      println(s"Before step: readData = ${dut.io.readData.peek().litValue}")
      dut.clock.step(1)
      println(s"After 1 step: readData = ${dut.io.readData.peek().litValue}")
      
      dut.io.writeEnable.poke(false.B)
      dut.clock.step(1)
      println(s"After 2 steps (write off): readData = ${dut.io.readData.peek().litValue}")
      
      dut.clock.step(1)
      println(s"After 3 steps: readData = ${dut.io.readData.peek().litValue}")
      
      // Test 2: Try reading at different addresses
      println("\n--- Reading after settling ---")
      dut.io.readAddr.poke(5.U)
      dut.clock.step(1)
      println(s"Read addr 5: readData = ${dut.io.readData.peek().litValue}")
      
      dut.io.readAddr.poke(0.U)
      dut.clock.step(1)
      println(s"Read addr 0: readData = ${dut.io.readData.peek().litValue}")
      
      dut.io.readAddr.poke(5.U)
      dut.clock.step(1)
      println(s"Read addr 5 again: readData = ${dut.io.readData.peek().litValue}")
    }
  }
  
  it should "test write for 2 cycles" in {
    test(new SimpleSyncReadMemModule).withAnnotations(Seq(WriteVcdAnnotation)) { dut =>
      println("\n=== Testing 2-cycle write ===")
      
      dut.io.writeEnable.poke(true.B)
      dut.io.writeAddr.poke(3.U)
      dut.io.writeData.poke(99.U)
      dut.io.readAddr.poke(3.U)
      println(s"Before step: readData = ${dut.io.readData.peek().litValue}")
      
      dut.clock.step(1)
      println(s"After 1 step (write still high): readData = ${dut.io.readData.peek().litValue}")
      
      dut.clock.step(1)
      println(s"After 2 steps (write still high): readData = ${dut.io.readData.peek().litValue}")
      
      dut.io.writeEnable.poke(false.B)
      dut.clock.step(1)
      println(s"After 3 steps (write off): readData = ${dut.io.readData.peek().litValue}")
    }
  }
}
