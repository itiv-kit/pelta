# Minimal OOC synthesis constraints for PE / ParityPE / TMRPE.
# Target: Artix-7 xc7a100tcsg324-1 at 100 MHz (10 ns period).
create_clock -name clock -period 10.0 [get_ports clock]

# reset is an async control input; exclude from timing analysis to keep the
# report focused on the synchronous datapath.
set_false_path -from [get_ports reset]
