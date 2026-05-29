package accelerator

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class TopAXISpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "AXILiteWrapper"

  /**
   * AXI4-Lite write transaction helper.
   * Requires the write FSM to be in sWIdle (awready && wready are T immediately).
   * Total cost: 3 clock steps (handshake → decode → response).
   */
  def axiWrite(dut: AXILiteWrapper, addr: Int, data: Int): Unit = {
    // Present both AW and W channels; FSM in sWIdle drives awready and wready high.
    dut.io.awaddr.poke(addr.U)
    dut.io.awvalid.poke(true.B)
    dut.io.wdata.poke(data.U)
    dut.io.wstrb.poke(0xF.U)
    dut.io.wvalid.poke(true.B)
    dut.io.bready.poke(true.B)
    // Step 1: sWIdle → sWDecode (handshake accepted)
    dut.clock.step()
    dut.io.awvalid.poke(false.B)
    dut.io.wvalid.poke(false.B)
    // Step 2: sWDecode → sWResp (decode fires; startPulse / write-enables active here)
    dut.clock.step()
    // Step 3: sWResp → sWIdle (bvalid consumed by bready)
    dut.clock.step()
    dut.io.bready.poke(false.B)
  }

  /**
   * AXI4-Lite read transaction helper.
   * Requires the read FSM to be in sRIdle.
   * Total cost: 3 clock steps (handshake → sample → response).
   * Returns the sampled rdata value.
   */
  def axiRead(dut: AXILiteWrapper, addr: Int): BigInt = {
    dut.io.araddr.poke(addr.U)
    dut.io.arvalid.poke(true.B)
    dut.io.rready.poke(true.B)
    // Step 1: sRIdle → sRData (AR accepted)
    dut.clock.step()
    dut.io.arvalid.poke(false.B)
    // Step 2: sRData → sRResp (register sampled into rDataReg)
    dut.clock.step()
    // sRResp drives rvalid=T and rdata=rDataReg combinationally — read here.
    val result = dut.io.rdata.peek().litValue
    // Step 3: sRResp → sRIdle (rvalid consumed by rready)
    dut.clock.step()
    dut.io.rready.poke(false.B)
    result
  }

  it should "instantiate and idle correctly" in {
    test(new AXILiteWrapper(2, 2, 8, 64)) { dut =>
      dut.io.awvalid.poke(false.B)
      dut.io.wvalid.poke(false.B)
      dut.io.arvalid.poke(false.B)
      dut.io.bready.poke(false.B)
      dut.io.rready.poke(false.B)
      dut.clock.step(3)
      val status = axiRead(dut, 0x04)
      assert((status & 1) == 0, s"busy bit should be 0, got $status")
      assert((status & 2) == 0, s"done bit should be 0, got $status")
    }
  }

  it should "write weight and activation via AXI and read back result" in {
    // 2x2 array, weights [[3,0],[0,2]], activations [4,5]
    // Column 0: 3*4 + 0*5 = 12
    // Column 1: 0*4 + 2*5 = 10
    test(new AXILiteWrapper(2, 2, 8, 64)) { dut =>
      dut.io.awvalid.poke(false.B)
      dut.io.wvalid.poke(false.B)
      dut.io.arvalid.poke(false.B)
      dut.io.bready.poke(false.B)
      dut.io.rready.poke(false.B)

      // Write weights row-major [3, 0, 0, 2]
      val weights = Seq(3, 0, 0, 2)
      for ((w, i) <- weights.zipWithIndex) {
        axiWrite(dut, 0x08, i)  // WEIGHT_ADDR = i
        axiWrite(dut, 0x0C, w)  // WEIGHT_DATA = w (triggers write enable)
      }

      // Write activations [4, 5]
      val acts = Seq(4, 5)
      for ((a, i) <- acts.zipWithIndex) {
        axiWrite(dut, 0x10, i)  // ACTIV_ADDR = i
        axiWrite(dut, 0x14, a)  // ACTIV_DATA = a (triggers write enable)
      }

      // Start with loadWeights=1: CTRL = 0b011 (start=1, loadWeights=1, accumulate=0)
      // axiWrite step 2 is sWDecode — startPulse fires and the controller sees
      // start=T, loadWeights=T → transitions to sLoadWeights.
      axiWrite(dut, 0x00, 0x03)

      // Poll STATUS until doneLatched bit is set.
      // For 2x2: sLoadWeights=5 cycles, sCompute=4 cycles → ~9 computation cycles.
      // Each axiRead costs 3 steps; 40 iterations = 120 steps >> 9 needed.
      var status = BigInt(0)
      var guard = 0
      while (((status >> 1) & 1) == 0 && guard < 40) {
        status = axiRead(dut, 0x04)
        guard += 1
      }
      assert(((status >> 1) & 1) == 1, s"done bit never set after $guard reads, status=$status")

      dut.clock.step(4)  // let result write loop complete (cols=2 write cycles + margin)

      // Read result col 0: write RESULT_ADDR=0 (triggers read enable), wait 1 cycle
      // for SyncReadMem latency, then read RESULT_DATA.
      axiWrite(dut, 0x18, 0)  // RESULT_ADDR=0, triggers resultReadEnable
      dut.clock.step(2)        // SyncReadMem read latency (1 cycle) + RegNext (1 cycle)
      val result0 = axiRead(dut, 0x1C)
      assert(result0 == 12, s"col 0: expected 12, got $result0")

      // Read result col 1
      axiWrite(dut, 0x18, 1)
      dut.clock.step(2)
      val result1 = axiRead(dut, 0x1C)
      assert(result1 == 10, s"col 1: expected 10, got $result1")
    }
  }
}
