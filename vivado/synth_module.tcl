# =============================================================================
# synth_module.tcl — Vivado 2025.2 batch OOC synthesis
#
#   PE-level :  PE / ParityPE / TMRPE
#   Top-level:  Top_NoSafety / Top_Parity / Top_TMR
#
# Usage:
#     cd vivado
#     vivado -mode batch -source synth_module.tcl -tclargs <MODULE_NAME> \
#            -log results/<MODULE_NAME>.log -journal results/<MODULE_NAME>.jou
#
# Prerequisites:
#   1. Generated SystemVerilog modules under ../generated/:
#         sbt "runMain accelerator.safety.EmitPE"
#         sbt "runMain accelerator.safety.EmitParityPE"
#         sbt "runMain accelerator.safety.EmitTMRPE"
#         sbt "runMain accelerator.EmitVerilog"        # Top NoSafety
#         sbt "runMain accelerator.EmitVerilogParity"  # Top Parity
#         sbt "runMain accelerator.EmitVerilogTMR"     # Top TMR
#   2. constraints.xdc alongside this script.
#   3. Sources staged by run_synth.sh under .staged_sv/ (PE-level flat,
#      Top-level under nosafety/, parity/, tmr/ subdirs).
#
# Output (under vivado/results/):
#     <MODULE_NAME>_util.rpt          utilization report
#     <MODULE_NAME>_timing.rpt        full timing summary (for WNS scalar)
#     <MODULE_NAME>_timing_worst.rpt  -nworst 1 worst path (for src/dst parse)
#     <MODULE_NAME>.dcp               post-synth checkpoint
# =============================================================================

# ---- Per-run configuration --------------------------------------------------
set ALLOWED_MODULES {PE ParityPE TMRPE Top_NoSafety Top_Parity Top_TMR}
set PART            xc7a100tcsg324-1
set SV_DIR          .staged_sv

if {$argc < 1} {
    error "Usage: vivado -mode batch -source synth_module.tcl -tclargs <MODULE_NAME>"
}
set MODULE_NAME [lindex $argv 0]
if {[lsearch -exact $ALLOWED_MODULES $MODULE_NAME] < 0} {
    error "Unknown MODULE_NAME: $MODULE_NAME (expected one of: $ALLOWED_MODULES)"
}

# Map MODULE_NAME → file list and synth_design -top name. All three Top
# variants declare `module Top` in their generated .sv (differentiated only
# by the staged subdirectory), so TOP_NAME is `Top` for those runs.
set TOP_FILES {Top.sv Array.sv SafePE.sv Controller.sv \
               Buffer.sv Buffer_2.sv mem_256x8.sv mem_256x32.sv}
switch -- $MODULE_NAME {
    PE {
        set DUT_FILES [list "${SV_DIR}/PE.sv"]
        set TOP_NAME  PE
    }
    ParityPE {
        set DUT_FILES [list "${SV_DIR}/ParityPE.sv"]
        set TOP_NAME  ParityPE
    }
    TMRPE {
        set DUT_FILES [list "${SV_DIR}/TMRPE.sv" "${SV_DIR}/PE.sv"]
        set TOP_NAME  TMRPE
    }
    Top_NoSafety {
        set sub "${SV_DIR}/nosafety"
        set DUT_FILES [list]
        foreach f [concat $TOP_FILES PE.sv]          { lappend DUT_FILES "${sub}/${f}" }
        set TOP_NAME Top
    }
    Top_Parity {
        set sub "${SV_DIR}/parity"
        set DUT_FILES [list]
        foreach f [concat $TOP_FILES ParityPE.sv]    { lappend DUT_FILES "${sub}/${f}" }
        set TOP_NAME Top
    }
    Top_TMR {
        set sub "${SV_DIR}/tmr"
        set DUT_FILES [list]
        foreach f [concat $TOP_FILES TMRPE.sv PE.sv] { lappend DUT_FILES "${sub}/${f}" }
        set TOP_NAME Top
    }
}

set RESULTS_DIR results
file mkdir $RESULTS_DIR

# ---- Synthesis body ---------------------------------------------------------
# Vivado exits 0 on TCL errors by default; catch and convert to a non-zero
# exit so `set -e` in the shell wrapper actually aborts on synth failure.
if {[catch {

    create_project -in_memory -part $PART
    read_verilog -sv {*}$DUT_FILES
    read_xdc constraints.xdc
    synth_design -top $TOP_NAME -mode out_of_context -flatten_hierarchy none

    report_utilization      -file ${RESULTS_DIR}/${MODULE_NAME}_util.rpt
    report_timing_summary   -file ${RESULTS_DIR}/${MODULE_NAME}_timing.rpt
    report_timing -nworst 1 -max_paths 1 -path_type full \
                            -file ${RESULTS_DIR}/${MODULE_NAME}_timing_worst.rpt
    write_checkpoint -force ${RESULTS_DIR}/${MODULE_NAME}.dcp

} err]} {
    puts stderr "ERROR during synthesis of $MODULE_NAME: $err"
    exit 1
}

puts "==== Synthesis complete: $MODULE_NAME ===="
exit 0
