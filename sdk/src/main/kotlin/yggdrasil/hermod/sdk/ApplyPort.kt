package yggdrasil.hermod.sdk

/** Result of reading one OPC-UA node: its value (string-encoded) + whether the StatusCode was Good. */
data class ReadResult(val value: String?, val good: Boolean)

/** Outcome of a write or call (incl. its confirm-by-read). `ok=false` means NOT confirmed. */
data class ApplyOutcome(val ok: Boolean, val detail: String)

/**
 * How the equipment clears the activate done-bit so the next command sees a fresh rising edge.
 * Only [ON_RELEASE] is implemented in the R1 direct apply path; the others are declared so a model
 * can carry equipment intent, and fail closed if used (see the design spec 2026-07-02).
 */
enum class DoneClearMode {
    ON_RELEASE,       // equipment clears done when the master de-asserts the trigger (standard rung handshake)
    EXPLICIT_RESET,   // equipment latches done until a separate reset/ack node is written
    MASTER_CLEARS;    // the master writes done=false directly (where done is client-writable)

    companion object {
        /** Map the Git-model kebab token to a mode; null for absent OR unknown (kept exact = fail-closed). */
        fun fromToken(token: String?): DoneClearMode? = when (token) {
            "on-release" -> ON_RELEASE
            "explicit-reset" -> EXPLICIT_RESET
            "master-clears" -> MASTER_CLEARS
            else -> null
        }
    }
}

/**
 * The physical-apply boundary (R1 = direct OPC-UA via Milo; R2 swaps in a bridge delegate).
 * Blocks depend ONLY on this interface; the worker injects an OpcUaApplyPort, tests a fake.
 */
interface ApplyPort {
    /** Read one node's current value (used for prior-value capture + read-back confirm). */
    fun read(nodeId: String): ReadResult
    /** Write `value` (string, converted per `type`) to `nodeId`, then confirm by read-back equality. */
    fun write(nodeId: String, type: String, value: String): ApplyOutcome
    /**
     * Invoke a command/method node, confirm by a rising edge (false->true) on `doneNodeId` within `timeoutMs`,
     * then complete the handshake per `doneClear` so the next call sees a fresh edge.
     */
    fun call(commandNodeId: String, doneNodeId: String, timeoutMs: Long,
             doneClear: DoneClearMode = DoneClearMode.ON_RELEASE): ApplyOutcome
}
