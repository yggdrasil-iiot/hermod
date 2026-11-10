package yggdrasil.hermod.runtime

/**
 * Workflow run params. The saga itself is driven by the precomputed plan (BoundWorkflow.plan() —
 * the compiled WorkflowIR) bound at worker startup — these inputs only carry per-run knobs so
 * the workflow args stay small and replay-safe.
 */
data class WorkflowInput(
    // failAtBlockId/slowMs are TEST-ONLY fault injection that currently ship in the production message
    // type (v0.1 pragmatism); they go away when a real failure-policy source exists in v0.2.
    val failAtBlockId: String? = null,  // test-only: forward throws permanently at this block id
    val autoApprove: Boolean = false,   // tests that don't drive the approve signal
    val slowMs: Long = 0,               // test-only: crash-script mid-activity window
    /** test-only: when set, slowMs applies ONLY to the node whose blockId == this (clean concurrency proof);
     *  null = slowMs applies workflow-wide (back-compat with the crash-recovery gate). */
    val slowAtBlockId: String? = null,
    val rows: List<Map<String, String?>> = emptyList(),  // seed rows when db.read is not used / for stress tests
    val useDbRead: Boolean = true,      // when true, db.read pulls from DB; else use input.rows
    val workflowName: String = "demo",  // which bound contract list to drive (keyed BoundWorkflow, §multi-workflow)
    /** v0.4b: when true, a node that exhausts its retries PARKS for an operator decision (retry/abort)
     *  instead of auto-compensating. Default false preserves the fire-and-forget auto-compensate path. */
    val interactive: Boolean = false,
)

data class WorkflowOutput(
    val completed: Boolean,
    val compensatedInReverseOrder: List<String> = emptyList(),
    val dedupCount: Int = 0,            // §9.1: rows after KEY_DEDUP (for count-delta assertion)
    val inputCount: Int = 0,           // §9.1: rows entering the KEY_DEDUP step
)
