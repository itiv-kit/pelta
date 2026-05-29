package accelerator.safety

import chisel3._
import circt.stage.ChiselStage
import accelerator.PE

object EmitPE extends App {
  ChiselStage.emitSystemVerilogFile(
    new PE(dataWidth = 8),
    args = Array("--target-dir", "generated", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization")
  )
  println("SystemVerilog generated in generated/PE.sv")
}

object EmitParityPE extends App {
  ChiselStage.emitSystemVerilogFile(
    new ParityPE(dataWidth = 8),
    args = Array("--target-dir", "generated", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization")
  )
  println("SystemVerilog generated in generated/ParityPE.sv")
}

object EmitTMRPE extends App {
  ChiselStage.emitSystemVerilogFile(
    new TMRPE(dataWidth = 8),
    args = Array("--target-dir", "generated", "--split-verilog"),
    firtoolOpts = Array("-disable-all-randomization")
  )
  println("SystemVerilog generated in generated/TMRPE.sv (+ PE.sv)")
}
