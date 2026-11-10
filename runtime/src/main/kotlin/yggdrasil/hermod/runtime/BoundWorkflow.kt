package yggdrasil.hermod.runtime

import yggdrasil.hermod.compiler.WorkflowIR

/**
 * Keyed holder of precomputed workflow plans (the engine-neutral [WorkflowIR]), one per workflow name.
 * The saga only ever READS an immutable IR for its own name (cheap, deterministic, replay-safe — R2/§8,
 * TMPRL1101). bind() is called once per workflow at worker startup, BEFORE factory.start().
 */
object BoundWorkflow {
    @Volatile private var byName: Map<String, WorkflowIR> = emptyMap()
    @Synchronized fun bind(name: String, plan: WorkflowIR) { byName = byName + (name to plan) }
    fun plan(name: String): WorkflowIR = byName[name] ?: error("workflow not bound: $name")
    fun boundNames(): Set<String> = byName.keys
}
