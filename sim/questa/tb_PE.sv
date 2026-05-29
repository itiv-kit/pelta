`timescale 1ns/1ps

// Deterministic single-MAC testbench for the baseline PE.
// Stimulus mirrors the third FaultInjectionSpec case: weight=3, activation=5,
// partialSumIn=0  =>  accumulator settles at GOLDEN = 32'd15.
//
// Timing contract with run_fi.tcl:
//   - run 60 ns halts simulation while this initial block is parked at
//     @(posedge clock) after the MAC completes (acc is already 15).
//   - TCL applies `force -freeze ${path}/accumulator[bit] $val` at t=60.
//   - run -all resumes; this block samples io_partialSumOut and writes
//     iter_result.txt, then $finish returns control to TCL.
//
// Baseline PE has no errorDetected port: error_detected is hardwired to 0.
module tb_PE;
    reg          clock = 0;
    reg          reset = 1;
    reg  [7:0]   io_weightIn = 0;
    reg  [7:0]   io_activationIn = 0;
    reg  [31:0]  io_partialSumIn = 0;
    reg          io_loadWeight = 0;
    reg          io_enable = 0;
    reg          io_clearAccum = 0;
    wire [7:0]   io_activationOut;
    wire [31:0]  io_partialSumOut;

    PE dut (.*);

    always #5 clock = ~clock;

    localparam [31:0] GOLDEN = 32'd15;

    integer f;
    initial begin
        // Reset for one cycle (posedge at t=5 latches reset, then deassert)
        #10 reset = 0;

        // Load weight = 3
        @(posedge clock); io_weightIn = 8'sd3; io_loadWeight = 1;
        @(posedge clock); io_loadWeight = 0;

        // One MAC: 3 * 5 + 0 = 15 (acc <= 15 at this posedge)
        @(posedge clock); io_enable = 1; io_activationIn = 8'sd5;
        @(posedge clock); io_enable = 0;

        // Settle — acc is stable at 15 from here on
        @(posedge clock);

        // <-- TCL `run 60 ns` halts here at t=60 and applies force -->

        // Let the forced bit propagate through combinational paths
        @(posedge clock);
        @(posedge clock);

        f = $fopen("iter_result.txt", "w");
        $fwrite(f, "FI_RESULT,%0d,%0d\n",
                1'b0,
                (io_partialSumOut == GOLDEN) ? 1'b1 : 1'b0);
        $fclose(f);
        $finish;
    end
endmodule
