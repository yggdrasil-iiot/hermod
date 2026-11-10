package yggdrasil.hermod.opcua

import yggdrasil.hermod.opcua.ncmd.SparkplugNcmdApplyPort
import yggdrasil.hermod.sdk.ApplyPort

/**
 * Selects the physical-apply implementation by `HERMOD_APPLY_MODE` (case-insensitive, whitespace ignored):
 *   - unset / blank / `ncmd` (default) → [SparkplugNcmdApplyPort]: Sparkplug B NCMD to Heimdall, which checks
 *     each command against its policy before it writes to the field.
 *   - `direct` → [OpcUaApplyPort]: direct Milo OPC UA writes that bypass Heimdall. Used only when named
 *     explicitly; [announce] prints a warning for it.
 *   - anything else → [IllegalArgumentException]. There is no silent fallback to direct.
 */
object ApplyPortFactory {
    const val ENV = "HERMOD_APPLY_MODE"

    enum class Mode { DIRECT, NCMD }

    fun mode(env: String? = System.getenv(ENV)): Mode = when (env?.trim()?.lowercase()) {
        null, "", "ncmd" -> Mode.NCMD
        "direct" -> Mode.DIRECT
        else -> throw IllegalArgumentException("$ENV='$env' is not supported; use 'ncmd' (the default) or 'direct'")
    }

    fun banner(who: String, mode: Mode): String = when (mode) {
        Mode.NCMD -> "[$who] apply mode: ncmd (opcua.write / opcua.call go through Heimdall)"
        Mode.DIRECT -> "[$who] WARNING: $ENV=direct - opcua.write / opcua.call write to OPC UA directly " +
            "and bypass Heimdall's policy check"
    }

    /**
     * Validates `HERMOD_APPLY_MODE` once at process start (an unknown value stops the worker before it polls)
     * and prints the mode; direct goes to stderr as a warning. Call from each worker `main`.
     */
    fun announce(who: String): Mode {
        val m = mode()
        if (m == Mode.DIRECT) System.err.println(banner(who, m)) else println(banner(who, m))
        return m
    }

    fun default(): ApplyPort = when (mode()) {
        Mode.DIRECT -> OpcUaApplyPort.default()
        Mode.NCMD   -> SparkplugNcmdApplyPort.default()
    }
}
