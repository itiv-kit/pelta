# =============================================================================
# run_fi.tcl — Questa Sim 2025.3 batch fault-injection sweep
#
# Usage:
#     cd sim/questa
#     vsim -c -do run_fi.tcl
#
# Prerequisites:
#   1. Generated SystemVerilog modules under ../../generated/:
#         sbt "runMain accelerator.safety.EmitPE"
#         sbt "runMain accelerator.safety.EmitParityPE"
#         sbt "runMain accelerator.safety.EmitTMRPE"
#   2. Per-module testbench tb_<MODULE_NAME>.sv in this directory.
#
# Output:
#   results/<MODULE_NAME>_fi.csv
#       module,acc_path,bit,value,effective_fault,error_detected,output_correct
#
# Retargeting to a different DUT:
#   Edit only the per-run configuration block below: MODULE_NAME, ACC_WIDTH,
#   ACC_PATHS, GOLDEN. The loop body is module-agnostic.
# =============================================================================

# ---- Per-run configuration --------------------------------------------------
set MODULE_NAME "TMRPE"                  ;# PE | ParityPE | TMRPE
set ACC_WIDTH   32
set TB_FILE     "tb_${MODULE_NAME}.sv"
set DUT_FILES   [list "../../generated/${MODULE_NAME}.sv"]

# TMRPE instantiates three PEs, so PE.sv must also be compiled.
if {$MODULE_NAME eq "TMRPE"} {
    lappend DUT_FILES "../../generated/PE.sv"
}

# Hierarchical paths to fault-injection targets.
# VERIFY against generated SV before first run, e.g.:
#     grep -nE "module |accumulator" ../../generated/TMRPE.sv
switch -- $MODULE_NAME {
    "PE"       { set ACC_PATHS [list "/tb_PE/dut/accumulator"] }
    "ParityPE" { set ACC_PATHS [list "/tb_ParityPE/dut/accumulator"] }
    "TMRPE"    { set ACC_PATHS [list "/tb_TMRPE/dut/pe0/accumulator" \
                                     "/tb_TMRPE/dut/pe1/accumulator" \
                                     "/tb_TMRPE/dut/pe2/accumulator"] }
    default    { error "Unknown MODULE_NAME: $MODULE_NAME" }
}

set RESULTS_CSV "results/${MODULE_NAME}_fi.csv"
set ITER_FILE   "iter_result.txt"

# Golden accumulator value after the deterministic TB stimulus
# (weight=3, activation=5, partialSumIn=0  =>  acc = 15 = 0x0000000F).
# Used to compute `effective_fault`: 1 iff the forced bit differs from the
# natural bit in GOLDEN, else the force is a no-op.
set GOLDEN 15

# ---- One-time setup ---------------------------------------------------------
file mkdir results
if {[file exists work]} { file delete -force work }
vlib work
vmap work work
vlog -sv -quiet {*}$DUT_FILES $TB_FILE

set fp [open $RESULTS_CSV w]
puts $fp "module,acc_path,bit,value,effective_fault,error_detected,output_correct"
close $fp

# +acc preserves signal accessibility for `force` after Questa optimization.
# -onfinish stop makes $finish return control to TCL instead of exiting vsim.
vsim -c -onfinish stop -voptargs=+acc work.tb_${MODULE_NAME}

# ---- Per-iteration helper ---------------------------------------------------
proc run_iter {acc_path bit_idx val} {
    global MODULE_NAME RESULTS_CSV ITER_FILE GOLDEN
    restart -force
    run 60 ns

    set effective 0
    if {$acc_path ne ""} {
        force -freeze "${acc_path}\[${bit_idx}\]" $val
        set golden_bit [expr {($GOLDEN >> $bit_idx) & 1}]
        set effective  [expr {$val != $golden_bit ? 1 : 0}]
    }
    run -all                                ;# TB runs to $finish

    if {![file exists $ITER_FILE]} {
        puts stderr "ERROR: $ITER_FILE not written ($acc_path bit=$bit_idx val=$val)"
        return [list 0 0 $effective]
    }
    set f [open $ITER_FILE r]
    set line [string trim [read $f]]
    close $f

    if {![regexp {FI_RESULT,(\d+),(\d+)} $line -> errd outok]} {
        puts stderr "ERROR: malformed FI_RESULT line: '$line'"
        return [list 0 0 $effective]
    }
    set csv [open $RESULTS_CSV a]
    puts $csv "$MODULE_NAME,$acc_path,$bit_idx,$val,$effective,$errd,$outok"
    close $csv
    return [list $errd $outok $effective]
}

# ---- Main loop --------------------------------------------------------------
set total 0          ;# every iteration (incl. baseline + no-op forces)
set effective 0      ;# iterations where the force actually flipped a bit
set detected 0       ;# error_detected==1 across all iterations
set detected_eff 0   ;# error_detected==1 among effective iterations only

# Iteration 0: fault-free baseline — sanity check.
set r [run_iter "" 0 0]
incr total
if {[lindex $r 0]} { incr detected }

# Injection sweep: foreach replica path × foreach bit × foreach value.
foreach acc_path $ACC_PATHS {
    for {set bit 0} {$bit < $ACC_WIDTH} {incr bit} {
        foreach val {0 1} {
            set r [run_iter $acc_path $bit $val]
            incr total
            set errd [lindex $r 0]
            set eff  [lindex $r 2]
            if {$errd}         { incr detected }
            if {$eff}          { incr effective }
            if {$eff && $errd} { incr detected_eff }
        }
    }
}

# ---- Summary ----------------------------------------------------------------
set dc_raw [expr {$total > 0     ? 100.0 * $detected     / $total     : 0.0}]
set dc_eff [expr {$effective > 0 ? 100.0 * $detected_eff / $effective : 0.0}]
puts ""
puts "==== Fault Injection Summary: $MODULE_NAME ===="
puts "Total injections:        $total"
puts "Effective (bit changed): $effective"
puts "Detected (all):          $detected"
puts "Detected (effective):    $detected_eff"
puts [format "DC raw       = %.2f%%   (detected / total)" $dc_raw]
puts [format "DC effective = %.2f%%   (detected / effective)" $dc_eff]
puts "Results:                 $RESULTS_CSV"
puts ""

quit -f
