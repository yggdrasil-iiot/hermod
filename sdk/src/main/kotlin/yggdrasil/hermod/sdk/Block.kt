package yggdrasil.hermod.sdk

typealias Record = Map<String, String?>

data class BlockInput(
    val rows: List<Record> = emptyList(),
    val params: Map<String, String> = emptyMap(),
    /** wired inputs by input name; populated for MULTI-input nodes only (single-input uses `rows`). */
    val namedInputs: Map<String, List<Record>> = emptyMap(),
    /** test-only failure injection: if equal to this block's id, forward throws permanently. */
    val failAtBlockId: String? = null,
    /** test-only: db.upsert sleeps this long so the crash script can kill the worker mid-activity. */
    val slowMs: Long = 0,
    /** the run (workflow) this block executes within; "-" when there is no run context (unit tests). */
    val runId: String = "-",
)
data class BlockOutput(
    val rows: List<Record> = emptyList(),
    /** stateBinding outputs as JSON strings (Jackson-safe across activities). */
    val boundState: Map<String, String> = emptyMap(),
)
data class CompensationContext(val alreadyAppliedHint: Boolean = true, val runId: String = "-")
data class CompensationAction(val kind: String, val detail: String) // RESTORE | CORRECT | NOOP

interface Block {
    val id: String
    fun forward(input: BlockInput): BlockOutput
    fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction =
        CompensationAction("NOOP", "no compensation declared")
}

class PermanentBlockFailure(message: String) : RuntimeException(message)
