package yggdrasil.hermod.opcua

import yggdrasil.hermod.opcua.ncmd.SparkplugNcmdApplyPort
import yggdrasil.hermod.sdk.ApplyPort

/**
 * Selects the physical-apply implementation by `HERMOD_APPLY_MODE`:
 *   - unset / `direct` (default) → [OpcUaApplyPort] (R1, direct Milo OPC-UA — byte-identical).
 *   - `ncmd` → [SparkplugNcmdApplyPort] (R2, self-bridge over MQTT + Sparkplug B).
 * Fail-safe default: any unknown value falls back to DIRECT.
 */
object ApplyPortFactory {
    enum class Mode { DIRECT, NCMD }
    fun mode(env: String? = System.getenv("HERMOD_APPLY_MODE")): Mode =
        if (env?.lowercase() == "ncmd") Mode.NCMD else Mode.DIRECT   // default/unset/"direct" → DIRECT
    fun default(): ApplyPort = when (mode()) {
        Mode.DIRECT -> OpcUaApplyPort.default()
        Mode.NCMD   -> SparkplugNcmdApplyPort.default()
    }
}
