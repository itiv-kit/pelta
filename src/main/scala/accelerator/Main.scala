package accelerator

import chisel3._

object TopMain extends App {
  println("Generating Verilog for Top module...")
  emitVerilog(new Top(rows = 4, cols = 4, dataWidth = 8, bufferDepth = 256), Seq("--target-dir", "generated").toArray)
  println("Verilog generated in generated/Top.v")
}

object PEMain extends App {
  println("Generating Verilog for PE module...")
  emitVerilog(new PE(dataWidth = 8), Seq("--target-dir", "generated").toArray)
  println("Verilog generated in generated/PE.v")
}

object ArrayMain extends App {
  println("Generating Verilog for Array module...")
  emitVerilog(new Array(rows = 4, cols = 4, dataWidth = 8), Seq("--target-dir", "generated").toArray)
  println("Verilog generated in generated/Array.v")
}
