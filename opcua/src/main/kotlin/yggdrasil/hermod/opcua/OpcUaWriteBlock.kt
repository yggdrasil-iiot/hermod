package yggdrasil.hermod.opcua

import yggdrasil.hermod.sdk.*

/**
 * `opcua.write` — staged setpoint write (stageRecipe).
 *
 * Reversible: captures prior node values in [BlockOutput.boundState] so [compensate] can restore
 * them. Fail-closed: any policy denial, EURange violation, or write failure throws
 * [PermanentBlockFailure] so the saga compensates immediately.
 *
 * **runId for audit:** comes from the block execution context — [BlockInput.runId] in forward,
 * [CompensationContext.runId] in compensate (the workflow runId; `"-"` when there is no run context).
 *
 * @param apply  physical apply port. Constructor param is null by default; the actual field is
 *               lazy so [BuiltinBlocks] can construct this at registry-assembly time without a live
 *               OPC-UA server (connection deferred to first [forward] call). Tests inject a
 *               [FakeApplyPort] by passing a non-null value.
 * @param model  site model (node catalogue, EURange, activate node ids).
 * @param policy command policy (deny-by-default authorization by logical node key).
 */
class OpcUaWriteBlock(
    apply: ApplyPort? = null,
    private val model: SiteModel = SiteModel.default(),
    private val policy: CommandPolicy = CommandPolicy.default(),
) : Block {

    override val id = "opcua.write"

    /** Lazy: defers OpcUaApplyPort.default() (Milo connect) to first forward() call. */
    private val effectiveApply: ApplyPort by lazy { apply ?: ApplyPortFactory.default() }

    /**
     * Validation gate: runs once on a valid model (memoized); re-fires on every forward() on an
     * invalid model because Kotlin lazy does NOT cache a thrown exception (correct fail-closed behavior).
     * Covers a bad HERMOD_OPCUA_MODEL env override at deploy; the build-time test (Task 1.3) is the
     * primary guard that a bad model never ships.
     */
    @Suppress("unused")
    private val modelChecked: Unit by lazy {
        val r = ModelValidator.validate(model, policy)
        if (!r.ok) throw PermanentBlockFailure("invalid OPC-UA model/policy: ${r.errors}")
        if (r.warnings.isNotEmpty()) System.err.println("opcua.write model warnings: ${r.warnings}")
    }

    /** Forces the one-time fail-closed model/policy validation (see [modelChecked]). */
    private fun ensureModelValid() { @Suppress("UNUSED_EXPRESSION") modelChecked }

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        ensureModelValid()

        val priorMap = mutableMapOf<String, String?>()   // logicalKey -> prior string value

        for (row in input.rows) {
            val logical = row["id"] ?: throw PermanentBlockFailure("row missing 'id' field")
            val value   = row["val"] ?: throw PermanentBlockFailure("row missing 'val' field (node=$logical)")

            // ① Policy check
            val decision = policy.evaluate(logical)
            if (!decision.allowed) {
                tryAudit(input.runId, id, logical, null, value, false, null, AuditOutcome.DENIED)
                throw PermanentBlockFailure("command denied by policy for node '$logical'")
            }

            // ② Model lookup + EURange check
            val nodeDef = try { model.node(logical) } catch (e: IllegalArgumentException) {
                tryAudit(input.runId, id, logical, null, value, true, decision.ruleId, AuditOutcome.FAILED)
                throw PermanentBlockFailure("unknown model node '$logical': ${e.message}")
            }
            val euRange = nodeDef.euRange
            if (euRange != null) {
                val v = value.toDoubleOrNull()
                    ?: throw PermanentBlockFailure("value '$value' is not numeric for EURange-bounded node '$logical'")
                if (v < euRange.low || v > euRange.high) {
                    tryAudit(input.runId, id, logical, nodeDef.nodeId, value, true, decision.ruleId, AuditOutcome.EURANGE_REJECT)
                    throw PermanentBlockFailure(
                        "value $v out of EURange [${euRange.low}, ${euRange.high}] for node '$logical'"
                    )
                }
            }

            // ③ Capture prior
            val prior = effectiveApply.read(nodeDef.nodeId)
            priorMap[logical] = prior.value

            // ④ Write
            val outcome = effectiveApply.write(nodeDef.nodeId, nodeDef.type, value)
            if (!outcome.ok) {
                tryAudit(input.runId, id, logical, nodeDef.nodeId, value, true, decision.ruleId, AuditOutcome.FAILED)
                throw PermanentBlockFailure("OPC-UA write failed for node '$logical': ${outcome.detail}")
            }

            // ⑤ Audit WRITTEN
            tryAudit(input.runId, id, logical, nodeDef.nodeId, value, true, decision.ruleId, AuditOutcome.WRITTEN)
        }

        return BlockOutput(
            rows = input.rows,
            boundState = mapOf("priorSetpoints" to OpcuaJson.write(priorMap)),
        )
    }

    override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction {
        val priorMap: Map<String, String?> = OpcuaJson.read(boundState["priorSetpoints"] ?: "{}")
        var restored = 0
        var failed = 0
        for ((logical, priorValue) in priorMap) {
            if (priorValue == null) continue   // unreadable prior — skip
            val nodeDef = try {
                model.node(logical)
            } catch (e: IllegalArgumentException) {
                // model drift: node no longer in the canonical model — skip, keep restoring the rest
                System.err.println("opcua.write compensate: skipping unknown model node '$logical' (model drift?): ${e.message}")
                continue
            }
            val outcome = effectiveApply.write(nodeDef.nodeId, nodeDef.type, priorValue)
            if (outcome.ok) {
                tryAudit(ctx.runId, id, logical, nodeDef.nodeId, priorValue, true, null, AuditOutcome.RESTORED)
                restored++
            } else {
                // Reverse write not confirmed (e.g. NCMD edge-deny / timeout): audit honestly as FAILED.
                System.err.println("opcua.write compensate: RESTORE not confirmed for node '$logical': ${outcome.detail}")
                tryAudit(ctx.runId, id, logical, nodeDef.nodeId, priorValue, true, null, AuditOutcome.FAILED)
                failed++
            }
        }
        val detail =
            if (failed == 0) "restored $restored setpoint(s)"
            else "restored $restored setpoint(s), $failed RESTORE(s) not confirmed"
        return CompensationAction("RESTORE", detail)
    }

    /**
     * Audit write is best-effort: a DB outage must not shadow the primary block failure or
     * prevent unit tests from running without a Postgres instance.
     */
    private fun tryAudit(
        runId: String, node: String, logicalNode: String, opcuaNode: String?,
        value: String?, allowed: Boolean, ruleId: String?, outcome: AuditOutcome,
    ) {
        try {
            CommandAudit.record(runId, node, logicalNode, opcuaNode, value, allowed, ruleId, outcome)
        } catch (_: java.sql.SQLException) {
            // Non-fatal: audit DB unavailable (e.g. unit test context). Outcome already proven by write gate.
        }
    }
}
