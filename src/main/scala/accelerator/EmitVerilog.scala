package accelerator

import circt.stage.ChiselStage
import scala.Array

object EmitVerilog extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top(rows = 4, cols = 4, dataWidth = 8, bufferDepth = 256),
    args = Array("--target-dir", "generated", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println("SystemVerilog generated in generated/ directory")
}

object EmitVerilogNoSafety extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top(rows = 4, cols = 4, dataWidth = 8, bufferDepth = 256, safety = NoSafety),
    args = Array("--target-dir", "generated/nosafety", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println("SystemVerilog generated in generated/nosafety/")
}

object EmitVerilogParity extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top(rows = 4, cols = 4, dataWidth = 8, bufferDepth = 256, safety = Parity),
    args = Array("--target-dir", "generated/parity", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println("SystemVerilog generated in generated/parity/")
}

object EmitVerilogTMR extends App {
  ChiselStage.emitSystemVerilogFile(
    new Top(rows = 4, cols = 4, dataWidth = 8, bufferDepth = 256, safety = TMR),
    args = Array("--target-dir", "generated/tmr", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info")
  )
  println("SystemVerilog generated in generated/tmr/")
}
