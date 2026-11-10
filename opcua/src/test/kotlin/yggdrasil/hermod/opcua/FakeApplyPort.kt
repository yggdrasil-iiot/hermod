package yggdrasil.hermod.opcua

import yggdrasil.hermod.sdk.ApplyOutcome
import yggdrasil.hermod.sdk.ApplyPort
import yggdrasil.hermod.sdk.ReadResult

/**
 * In-memory [ApplyPort] for block unit tests (no Milo, no DB).
 *
 * @param readBackMismatch  when true, [write] returns ok=false to simulate a read-back mismatch.
 * @param callTimeout       when true, [call] returns ok=false to simulate a rising-edge timeout.
 */
class FakeApplyPort(
    private val readBackMismatch: Boolean = false,
    private val callTimeout: Boolean = false,
) : ApplyPort {

    private val store = mutableMapOf<String, String>()
    val calls = mutableListOf<Triple<String, String, Long>>()   // (commandNodeId, doneNodeId, timeoutMs)
    val doneClears = mutableListOf<yggdrasil.hermod.sdk.DoneClearMode>()   // parallel to calls

    /** Pre-load a node value so [read] returns it before any [write]. */
    fun seed(nodeId: String, value: String) { store[nodeId] = value }

    override fun read(nodeId: String): ReadResult =
        ReadResult(value = store[nodeId], good = store.containsKey(nodeId))

    override fun write(nodeId: String, type: String, value: String): ApplyOutcome {
        return if (readBackMismatch) {
            ApplyOutcome(ok = false, detail = "simulated read-back mismatch on $nodeId")
        } else {
            store[nodeId] = value
            ApplyOutcome(ok = true, detail = "written $value to $nodeId")
        }
    }

    override fun call(commandNodeId: String, doneNodeId: String, timeoutMs: Long,
                      doneClear: yggdrasil.hermod.sdk.DoneClearMode): ApplyOutcome {
        calls.add(Triple(commandNodeId, doneNodeId, timeoutMs))
        doneClears.add(doneClear)
        return if (callTimeout) {
            ApplyOutcome(ok = false, detail = "simulated rising-edge timeout on $doneNodeId")
        } else {
            ApplyOutcome(ok = true, detail = "rising-edge confirmed on $doneNodeId")
        }
    }

    /** Current value stored for a node id (for assertions). */
    fun get(nodeId: String): String? = store[nodeId]
}
