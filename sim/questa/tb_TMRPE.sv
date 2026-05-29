`timescale 1ns/1ps

// Deterministic single-MAC testbench for TMRPE.
// See tb_PE.sv for the timing contract with run_fi.tcl.
//
// TMRPE instantiates three PE submodules (pe0/pe1/pe2). The TCL script forces
// one bit of one replica's accumulator at a time and iterates over all three
// paths in ACC_PATHS.
//
// Expected behavior on an effective single-replica force:
//   error_detected = 1   (voter sees a disagreement)
//   output_correct = 1   (majority voter masks the corrupted replica)
module tb_TMRPE;
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
    wire         io_errorDetected;

    TMRPE dut (.*);

    always #5 clock = ~clock;

    localparam [31:0] GOLDEN = 32'd15;

    integer f;
    initial begin
        #10 reset = 0;

        @(posedge clock); io_weightIn = 8'sd3; io_loadWeight = 1;
        @(posedge clock); io_loadWeight = 0;

        @(posedge clock); io_enable = 1; io_activationIn = 8'sd5;
        @(posedge clock); io_enable = 0;

        @(posedge clock);

        // <-- TCL `run 60 ns` halts here at t=60 and applies force -->

        @(posedge clock);
        @(posedge clock);

        f = $fopen("iter_result.txt", "w");
        $fwrite(f, "FI_RESULT,%0d,%0d\n",
                io_errorDetected,
                (io_partialSumOut == GOLDEN) ? 1'b1 : 1'b0);
        $fclose(f);
        $finish;
    end
endmodule
