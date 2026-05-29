package accelerator

import chisel3._
import chisel3.util._

/**
 * AXI4-Lite slave wrapper around Top.
 *
 * Register map (byte-addressed, 32-bit words):
 *   0x00  CTRL        W   [0]=start (1-cycle pulse), [1]=loadWeights, [2]=accumulate
 *   0x04  STATUS      R   [0]=busy, [1]=done (latched; cleared on next start)
 *   0x08  WEIGHT_ADDR W   weight buffer write address
 *   0x0C  WEIGHT_DATA W   weight value – writing triggers the write enable for 1 cycle
 *   0x10  ACTIV_ADDR  W   activation buffer write address
 *   0x14  ACTIV_DATA  W   activation value – writing triggers the write enable for 1 cycle
 *   0x18  RESULT_ADDR W   result buffer read address – writing triggers the read enable
 *   0x1C  RESULT_DATA R   result data (1 cycle after RESULT_ADDR write)
 */
class AXILiteWrapper(
  rows: Int = 4,
  cols: Int = 4,
  dataWidth: Int = 8,
  bufferDepth: Int = 256,
  saturate: Boolean = false,
  axiAddrWidth: Int = 8
) extends Module {

  val io = IO(new Bundle {
    // AXI4-Lite write address channel
    val awaddr  = Input(UInt(axiAddrWidth.W))
    val awvalid = Input(Bool())
    val awready = Output(Bool())
    // AXI4-Lite write data channel
    val wdata   = Input(UInt(32.W))
    val wstrb   = Input(UInt(4.W))
    val wvalid  = Input(Bool())
    val wready  = Output(Bool())
    // AXI4-Lite write response channel
    val bresp   = Output(UInt(2.W))
    val bvalid  = Output(Bool())
    val bready  = Input(Bool())
    // AXI4-Lite read address channel
    val araddr  = Input(UInt(axiAddrWidth.W))
    val arvalid = Input(Bool())
    val arready = Output(Bool())
    // AXI4-Lite read data channel
    val rdata   = Output(UInt(32.W))
    val rresp   = Output(UInt(2.W))
    val rvalid  = Output(Bool())
    val rready  = Input(Bool())
  })

  val top = Module(new Top(rows, cols, dataWidth, bufferDepth, saturate))

  // ---- Control registers ------------------------------------------------
  val loadWeightsReg = RegInit(false.B)
  val accumulateReg  = RegInit(false.B)
  val weightAddrReg  = RegInit(0.U(log2Ceil(bufferDepth).W))
  val activAddrReg   = RegInit(0.U(log2Ceil(bufferDepth).W))
  val resultAddrReg  = RegInit(0.U(log2Ceil(bufferDepth).W))

  // Latched done: set when top.io.done pulses, cleared on next start pulse.
  val doneLatched = RegInit(false.B)

  // One-cycle pulse wires driven from the write decoder
  val startPulse        = WireDefault(false.B)
  val weightWriteEnable = WireDefault(false.B)
  val weightWriteData   = WireDefault(0.U(dataWidth.W))
  val activWriteEnable  = WireDefault(false.B)
  val activWriteData    = WireDefault(0.U(dataWidth.W))
  val resultReadEnable  = WireDefault(false.B)

  // ---- done latch -------------------------------------------------------
  when(startPulse)      { doneLatched := false.B }
  .elsewhen(top.io.done){ doneLatched := true.B  }

  // ---- Top connections --------------------------------------------------
  top.io.start                 := startPulse
  top.io.loadWeights           := loadWeightsReg
  top.io.accumulate            := accumulateReg
  top.io.weightWriteEnable     := weightWriteEnable
  top.io.weightWriteAddr       := weightAddrReg
  top.io.weightWriteData       := weightWriteData
  top.io.activationWriteEnable := activWriteEnable
  top.io.activationWriteAddr   := activAddrReg
  top.io.activationWriteData   := activWriteData
  top.io.resultReadEnable      := resultReadEnable
  top.io.resultReadAddr        := resultAddrReg

  // Result data captured 1 cycle after the read enable fires.
  val resultDataReg = RegNext(top.io.resultReadData.asUInt, 0.U)

  // =========================================================================
  // AXI4-Lite Write FSM
  // Accepts AW and W channels simultaneously (both must be valid in the same
  // cycle for the handshake to complete), decodes the register, then returns
  // a write response on the B channel.
  // =========================================================================
  val sWIdle :: sWDecode :: sWResp :: Nil = Enum(3)
  val wState   = RegInit(sWIdle)
  val awAddrReg = RegInit(0.U(axiAddrWidth.W))
  val wDataReg  = RegInit(0.U(32.W))

  io.awready := false.B
  io.wready  := false.B
  io.bvalid  := false.B
  io.bresp   := 0.U

  switch(wState) {
    is(sWIdle) {
      // Accept both channels in the same cycle (both must be valid).
      when(io.awvalid && io.wvalid) {
        io.awready := true.B
        io.wready  := true.B
        awAddrReg  := io.awaddr
        wDataReg   := io.wdata
        wState     := sWDecode
      }
    }

    is(sWDecode) {
      val addr = awAddrReg
      val data = wDataReg
      switch(addr(7, 0)) {
        is(0x00.U) {
          startPulse := data(0)
          // Override loadWeights and accumulate combinationally for the same
          // cycle startPulse fires; the registers lag by one clock edge.
          top.io.loadWeights := data(1)
          top.io.accumulate  := data(2)
          loadWeightsReg     := data(1)
          accumulateReg      := data(2)
        }
        is(0x08.U) { weightAddrReg := data(log2Ceil(bufferDepth) - 1, 0) }
        is(0x0C.U) {
          weightWriteEnable := true.B
          weightWriteData   := data(dataWidth - 1, 0)
        }
        is(0x10.U) { activAddrReg := data(log2Ceil(bufferDepth) - 1, 0) }
        is(0x14.U) {
          activWriteEnable := true.B
          activWriteData   := data(dataWidth - 1, 0)
        }
        is(0x18.U) {
          resultAddrReg         := data(log2Ceil(bufferDepth) - 1, 0)
          top.io.resultReadAddr := data(log2Ceil(bufferDepth) - 1, 0)  // override lag
          resultReadEnable      := true.B
        }
      }
      wState := sWResp
    }

    is(sWResp) {
      io.bvalid := true.B
      io.bresp  := 0.U
      when(io.bready) {
        wState := sWIdle
      }
    }
  }

  // =========================================================================
  // AXI4-Lite Read FSM
  // =========================================================================
  val sRIdle :: sRData :: sRResp :: Nil = Enum(3)
  val rState    = RegInit(sRIdle)
  val arAddrReg = RegInit(0.U(axiAddrWidth.W))
  val rDataReg  = RegInit(0.U(32.W))

  io.arready := false.B
  io.rvalid  := false.B
  io.rresp   := 0.U
  io.rdata   := 0.U

  switch(rState) {
    is(sRIdle) {
      when(io.arvalid) {
        io.arready := true.B
        arAddrReg  := io.araddr
        rState     := sRData
      }
    }

    is(sRData) {
      switch(arAddrReg(7, 0)) {
        is(0x04.U) { rDataReg := Cat(0.U(30.W), doneLatched, top.io.busy) }
        is(0x1C.U) { rDataReg := resultDataReg }
      }
      rState := sRResp
    }

    is(sRResp) {
      io.rvalid := true.B
      io.rdata  := rDataReg
      io.rresp  := 0.U
      when(io.rready) {
        rState := sRIdle
      }
    }
  }
}
