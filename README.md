# Pelta

A weight-stationary 4×4 systolic array (Chisel) with an AXI4-Lite control interface
and optional per-PE fault detection (parity or TMR), selected at elaboration time
via `SafetyMode`.

## Quick start

Prerequisites: JDK 11+, Scala 2.13, sbt 1.9+.

```bash
sbt compile
sbt test

# Generate SystemVerilog (outputs land in generated/)
sbt "runMain accelerator.EmitVerilog"           # baseline Top
sbt "runMain accelerator.EmitVerilogParity"     # Top with Parity-protected PEs
sbt "runMain accelerator.EmitVerilogTMR"        # Top with TMR-protected PEs
sbt "runMain accelerator.safety.EmitParityPE"   # ParityPE on its own
sbt "runMain accelerator.safety.EmitTMRPE"      # TMRPE on its own
```

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for full details.

- **PE** — weight-stationary MAC, optional signed saturation.
- **Array** — `rows × cols` systolic grid of `SafePE`s.
- **Buffer** — `SyncReadMem`-backed direct-addressed SRAM.
- **Controller** — four-state FSM (Idle → LoadWeights → Compute → Done).
- **Top** — integrator with time-multiplexed weight/activation loading and tile accumulation.
- **AXILiteWrapper** — AXI4-Lite slave with an eight-register memory map.
- **SafePE** / **SafetyMode** — elaboration-time dispatcher selecting `NoSafety | Parity | TMR`.
- **ParityPE** — accumulator shadowed by a parity register (encoded-register pattern).
- **TMRPE** — three replica PEs with bitwise majority voting.

## Safety

`SafetyMode` swaps every PE in the array for a hardened variant and exposes a single
`errorDetected` signal (OR-reduced across all PEs).

- **Parity**: 1-bit `parityReg` written on the same edge as the accumulator. Mismatch on
  read fires `errorDetected`. Detects single-bit upsets inside the storage cell.
- **TMR**: three replicas, bitwise majority vote on `partialSumOut`. Any replica
  disagreeing with the voted output asserts `errorDetected`.

## License

MIT License. See [LICENSE](LICENSE).
