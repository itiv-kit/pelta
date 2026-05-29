package accelerator

import chisel3._
import chisel3.util._
import chisel3.SyncReadMem._

/**
 * Memory buffer for activations, weights, and results
 * Provides simple read/write interface
 * 
 * @param depth Number of entries in buffer
 * @param width Width of each entry in bits
 */
class Buffer(depth: Int = 256, width: Int = 32) extends Module {
  val io = IO(new Bundle {
    // Write port
    val writeEnable = Input(Bool())
    val writeAddr = Input(UInt(log2Ceil(depth).W))
    val writeData = Input(UInt(width.W))
    
    // Read port
    val readEnable = Input(Bool())
    val readAddr = Input(UInt(log2Ceil(depth).W))
    val readData = Output(UInt(width.W))
    
    // Status
    val full = Output(Bool())
    val empty = Output(Bool())
  })

  // Memory array, SRAM style
  val mem = SyncReadMem(depth, UInt(width.W), WriteFirst)
  
  // Entry counter
  val entryCount = RegInit(0.U(log2Ceil(depth + 1).W))
  
  // Write logic
  when(io.writeEnable){
    mem.write(io.writeAddr, io.writeData)
    when(!io.readEnable) {
      entryCount := entryCount + 1.U
    }
  }
  
  // Read logic
  io.readData := mem.read(io.readAddr, io.readEnable)
  when(io.readEnable) {
    when(!io.writeEnable) {
      entryCount := entryCount - 1.U
    }
  }

  // Status signals
  io.full := entryCount === depth.U
  io.empty := entryCount === 0.U
}
