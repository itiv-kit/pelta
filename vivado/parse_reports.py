#!/usr/bin/env python3
"""Extract LUT/FF/WNS/critical-path from Vivado OOC reports and write CSV.

Reads from vivado/results/{MODULE}_{util,timing,timing_worst}.rpt for each
PE-level module (PE / ParityPE / TMRPE) and full-accelerator Top variant
(Top_NoSafety / Top_Parity / Top_TMR), and writes
vivado/results/synthesis_summary.csv.

Fail-loud on any missing report or unparseable field — never writes a partial
CSV row.
"""
import csv
import re
import sys
from pathlib import Path

MODULES = ["PE", "ParityPE", "TMRPE",
           "Top_NoSafety", "Top_Parity", "Top_TMR"]
CLOCK_PERIOD_NS = 10.0
RESULTS_DIR = Path(__file__).parent / "results"


def _require(path):
    if not path.is_file() or path.stat().st_size == 0:
        sys.exit("ERROR: missing or empty report: {}".format(path))
    return path.read_text()


def parse_util(text):
    """First 'Slice LUTs' / 'Slice Registers' row in the utilization table.

    Vivado annotates Slice LUTs with a trailing '*' in OOC mode (warning that
    the post-implementation count may be lower), so allow it.
    """
    lut_match = re.search(r"^\|\s+Slice LUTs\*?\s*\|\s+(\d+)\s+\|", text, re.MULTILINE)
    ff_match = re.search(r"^\|\s+Slice Registers\s*\|\s+(\d+)\s+\|", text, re.MULTILINE)
    if not lut_match or not ff_match:
        sys.exit("ERROR: could not find Slice LUTs/Registers rows in utilization report")
    return int(lut_match.group(1)), int(ff_match.group(1))


def parse_wns(text):
    """Worst Negative Slack from the Design Timing Summary table.

    Format (post the column-header dashes line):
        WNS(ns)      TNS(ns)  ...
        -------      -------  ...
          2.757        0.000  ...
    Pick the first numeric on the data row.
    """
    m = re.search(
        r"Design Timing Summary.*?\n\s*-+\s+-+.*?\n\s*(-?\d+\.\d+)",
        text,
        re.DOTALL,
    )
    if not m:
        sys.exit("ERROR: could not find WNS in timing summary report")
    return float(m.group(1))


def parse_path(text):
    """Source / Destination pin from a `report_timing -nworst 1` report."""
    src = re.search(r"^\s+Source:\s+(\S+)", text, re.MULTILINE)
    dst = re.search(r"^\s+Destination:\s+(\S+)", text, re.MULTILINE)
    if not src or not dst:
        sys.exit("ERROR: could not find Source/Destination in worst-path report")
    return src.group(1), dst.group(1)


def collect(module):
    util_path = RESULTS_DIR / "{}_util.rpt".format(module)
    summary_path = RESULTS_DIR / "{}_timing.rpt".format(module)
    worst_path = RESULTS_DIR / "{}_timing_worst.rpt".format(module)

    util_text = _require(util_path)
    summary_text = _require(summary_path)
    worst_text = _require(worst_path)

    luts, ffs = parse_util(util_text)
    wns = parse_wns(summary_text)
    src, dst = parse_path(worst_text)
    fmax = 1000.0 / (CLOCK_PERIOD_NS - wns)

    return {
        "module": module,
        "luts": luts,
        "ffs": ffs,
        "wns_ns": "{:.3f}".format(wns),
        "fmax_mhz": "{:.2f}".format(fmax),
        "critical_path_src": src,
        "critical_path_dst": dst,
    }


def main():
    rows = [collect(m) for m in sorted(MODULES)]
    fields = ["module", "luts", "ffs", "wns_ns", "fmax_mhz",
              "critical_path_src", "critical_path_dst"]

    out_path = RESULTS_DIR / "synthesis_summary.csv"
    with out_path.open("w", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    stdout_writer = csv.DictWriter(sys.stdout, fieldnames=fields)
    stdout_writer.writeheader()
    stdout_writer.writerows(rows)
    print("\nWrote {}".format(out_path), file=sys.stderr)


if __name__ == "__main__":
    main()
