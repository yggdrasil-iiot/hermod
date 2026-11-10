package yggdrasil.hermod.opcua

import yggdrasil.hermod.sdk.*

/**
 * `opcua.call` — irreversible recipe activation (activateRecipe).
 *
 * Invokes the model's activate command node via a rising-edge boolean trigger, then confirms
 * by polling the done node. Fail-closed: if the done node never rises within the timeout,
 * throws [PermanentBlockFailure] so the saga compensates upstream staged steps.
 *
 * No compensation override: manifest declares IRREVERSIBLE/NONE. The saga's reverse-topo unwind
 * compensates any already-completed [OpcUaWriteBlock] steps when this block fails.
 *
 * **runId for audit:** comes from the block execution context — [BlockInput.runId] in forward
 * (the workflow runId; `"-"` when there is no run context).
 *
 * @param apply  physical apply port. Lazy-default so [BuiltinBlocks] can construct without a
 *               live OPC-UA server. Tests inject a [FakeApplyPort].
 * @param model  site model (activate command + done node ids).
 */
class OpcUaCallBlock(
    apply: ApplyPort? = null,
    private val model: SiteModel = SiteModel.default(),
) : Block {

    override val id = "opcua.call"

    /** Lazy: defers OpcUaApplyPort.default() (Milo connect) to first forward() call. */
    private val effectiveApply: ApplyPort by lazy { apply ?: ApplyPortFactory.default() }

    /**
     * Validation gate: runs once on a valid model (memoized); re-fires on every forward() on an
     * invalid model because Kotlin lazy does NOT cache a thrown exception (correct fail-closed behavior).
     */
    @Suppress("unused")
    private val modelChecked: Unit by lazy {
        // model-only validation (this block holds no CommandPolicy; the policy gate lives in opcua.write)
        val r = ModelValidator.validateModel(model)
        if (!r.ok) throw PermanentBlockFailure("invalid OPC-UA model: ${r.errors}")
        if (r.warnings.isNotEmpty()) System.err.println("opcua.call model warnings: ${r.warnings}")
    }

    /** Forces the one-time fail-closed model/policy validation (see [modelChecked]). */
    private fun ensureModelValid() { @Suppress("UNUSED_EXPRESSION") modelChecked }

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        ensureModelValid()

        val commandNodeId = model.activate.command.nodeId
        val doneNodeId    = model.activate.doneNode.nodeId
        val doneClear     = DoneClearMode.fromToken(model.activate.doneClear) ?: DoneClearMode.ON_RELEASE

        val outcome = effectiveApply.call(commandNodeId, doneNodeId, timeoutMs = 30_000L, doneClear = doneClear)
        if (!outcome.ok) {
            tryAudit(input.runId, id, "activate", commandNodeId, null, true, null, AuditOutcome.FAILED)
            throw PermanentBlockFailure("OPC-UA activate failed: ${outcome.detail}")
        }
        tryAudit(input.runId, id, "activate", commandNodeId, null, true, null, AuditOutcome.CONFIRMED)
        return BlockOutput(rows = input.rows)
    }

    private fun tryAudit(
        runId: String, node: String, logicalNode: String, opcuaNode: String?,
        value: String?, allowed: Boolean, ruleId: String?, outcome: AuditOutcome,
    ) {
        try {
            CommandAudit.record(runId, node, logicalNode, opcuaNode, value, allowed, ruleId, outcome)
        } catch (_: java.sql.SQLException) {
            // Non-fatal: audit DB unavailable (unit test context). Outcome proven by rising-edge confirm.
        }
    }
}
