package accelerator

sealed trait SafetyMode
case object NoSafety extends SafetyMode
case object Parity   extends SafetyMode
case object TMR      extends SafetyMode
