#!/bin/bash
# =============================================================================
# run_synth.sh — Batch OOC synthesis sweep
#
#   PE-level :  PE / ParityPE / TMRPE         (per-PE area + Fmax)
#   Top-level:  Top_NoSafety / Top_Parity / Top_TMR
#               (full accelerator: 4×4 SafePE array + buffers + controller)
#
# Usage:
#     bash vivado/run_synth.sh                 # uses pinned Vivado module
#     VIVADO_MODULE=xilinx/vivado/2024.2 bash vivado/run_synth.sh   # override
#
# Outputs go to vivado/results/. Anchors to vivado/ so .Xil/, .jou, .log all
# stay contained there.
# =============================================================================
set -euo pipefail

# Anchor to vivado/ regardless of where this script was invoked from.
cd "$(dirname "$(readlink -f "$0")")"

# Initialise Environment Modules and load Vivado.
source /etc/profile.d/modules.sh 2>/dev/null || true
: "${VIVADO_MODULE:=xilinx/vivado/2025.2}"
module load "$VIVADO_MODULE"

if ! command -v vivado >/dev/null; then
    echo "ERROR: vivado not on PATH after 'module load $VIVADO_MODULE'" >&2
    exit 1
fi

mkdir -p results

# ---- Stage SystemVerilog inputs --------------------------------------------
# Vivado's cross-boundary optimisation collapses functionally-equivalent
# submodules even with -flatten_hierarchy none. For honest TMR area numbers
# we inject (* DONT_TOUCH = "yes" *) pragmas before each PE instance in
# TMRPE.sv. PE.sv and ParityPE.sv are copied verbatim.
#
# At Top_TMR scale the same TMRPE source is instantiated 16× (one per
# SafePE wrapper), so the 3 DONT_TOUCH pragmas inject into all 48 PE
# replicas via the shared module definition.
STAGE_DIR=.staged_sv
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

# --- PE-level (existing) ---
cp ../generated/PE.sv ../generated/ParityPE.sv "$STAGE_DIR/"
sed -E '/^  PE pe[012] \(/i\  (* DONT_TOUCH = "yes" *)' \
    ../generated/TMRPE.sv > "$STAGE_DIR/TMRPE.sv"

n_pragmas=$(grep -c 'DONT_TOUCH' "$STAGE_DIR/TMRPE.sv" || true)
if [[ "$n_pragmas" -ne 3 ]]; then
    echo "ERROR: expected 3 DONT_TOUCH pragmas in staged TMRPE.sv, got $n_pragmas" >&2
    exit 1
fi

# --- Top-level variants ---
# Each variant is emitted into its own subdirectory by EmitVerilog* (root
# for default NoSafety, generated/parity/, generated/tmr/). All three .sv
# files declare `module Top`; we differentiate by staging path and pass
# `-top Top` in the TCL.
TOP_COMMON_FILES=(Top.sv Array.sv SafePE.sv Controller.sv \
                  Buffer.sv Buffer_2.sv mem_256x8.sv mem_256x32.sv)

mkdir -p "$STAGE_DIR/nosafety"
for f in "${TOP_COMMON_FILES[@]}" PE.sv; do
    cp "../generated/$f" "$STAGE_DIR/nosafety/$f"
done

mkdir -p "$STAGE_DIR/parity"
for f in "${TOP_COMMON_FILES[@]}" ParityPE.sv; do
    cp "../generated/parity/$f" "$STAGE_DIR/parity/$f"
done

mkdir -p "$STAGE_DIR/tmr"
for f in "${TOP_COMMON_FILES[@]}" PE.sv; do
    cp "../generated/tmr/$f" "$STAGE_DIR/tmr/$f"
done
sed -E '/^  PE pe[012] \(/i\  (* DONT_TOUCH = "yes" *)' \
    ../generated/tmr/TMRPE.sv > "$STAGE_DIR/tmr/TMRPE.sv"

n_top_tmr=$(grep -c 'DONT_TOUCH' "$STAGE_DIR/tmr/TMRPE.sv" || true)
if [[ "$n_top_tmr" -ne 3 ]]; then
    echo "ERROR: expected 3 DONT_TOUCH pragmas in staged tmr/TMRPE.sv, got $n_top_tmr" >&2
    exit 1
fi

for MODULE in PE ParityPE TMRPE Top_NoSafety Top_Parity Top_TMR; do
    echo "=== Synthesizing $MODULE ==="
    vivado -mode batch -source synth_module.tcl -tclargs "$MODULE" \
           -log "results/${MODULE}.log" \
           -journal "results/${MODULE}.jou"

    # Belt-and-braces: confirm the reports were actually produced. The TCL
    # script now exits 1 on errors, but if Vivado itself crashes the shell
    # would otherwise march on to parse_reports.py with stale files.
    for rpt in "results/${MODULE}_util.rpt" "results/${MODULE}_timing_worst.rpt"; do
        if [[ ! -s "$rpt" ]]; then
            echo "ERROR: missing or empty report: $rpt" >&2
            exit 1
        fi
    done
done

echo "=== Parsing reports ==="
python3 parse_reports.py
