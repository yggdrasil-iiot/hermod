package koshei.runtime

import koshei.blocks.Db
import koshei.compiler.WorkflowCompiler
import koshei.compiler.WorkflowIR
import koshei.core.*
import koshei.dispatch.DispatchAssembly
import koshei.registry.*

/**
 * Builds the dynamic [Registry] (builtins static + plugins via the Postgres index/jar store) and
 * compiles a WorkflowDef into its plan — the primary product is now the engine-neutral [WorkflowIR]
 * (the ordered contract list is a legacy lowering for the CLI temporal target). Compilation is precomputed ONCE off the
 * workflow thread (R2/§8: keep the workflow thread cheap — TMPRL1101). Builtin resolution is DB-free
 * (Registry.resolve checks builtins before the index), so builtin-only v0.1 workflows never touch the
 * plugin DB.
 */
object RuntimeAssembly {
    /** Test-only augmentation (gate #2): present only under KOSHEI_TEST_BLOCKS=1. The neutral builtin
     *  loading itself now lives in [DispatchAssembly] (shared with :conductor-runtime). */
    private val extraBuiltins: Map<String, BlockContract> by lazy {
        if (System.getenv("KOSHEI_TEST_BLOCKS") == "1") mapOf(testStringSink()) else emptyMap()
    }

    /** Test-only contract (gate #2): a `string`-input sink to trigger a compile-time type mismatch against
     *  the all-`Record[]` builtins. Compile-only — never executed (mistyped.yaml fails type-check before run)
     *  and never bound by the worker (fixture lives off-classpath). Present only under KOSHEI_TEST_BLOCKS=1. */
    private fun testStringSink(): Pair<String, BlockContract> =
        "test.stringsink#1.0.0" to BlockContract(
            id = "test.stringsink", version = "1.0.0", category = BlockCategory.sink,
            inputs = listOf(IoSpec("text", "string")), outputs = emptyList(),
            forwardHandler = "koshei.dispatch.BuiltinBlocks",   // inert: compile-only fixture, never dispatched
            idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
            compensation = CompensationSpec(Reversibility.IRREVERSIBLE, CompensationKind.NONE),
            retry = RetrySpec(1, 100, 1000),
        )

    /**
     * Construct a [Registry] over the given index/store with the static builtins and a real
     * PluginLoader-based handlerLoadCheck (a publish probe (PluginLoader.probe): the forwardHandler must
     * be a public concrete koshei.sdk.Block with a public no-arg ctor, checked without running plugin
     * code). Exposed for tests that supply a container-backed index.
     */
    fun buildRegistry(index: BlockIndex, store: BlockStore): Registry =
        DispatchAssembly.buildRegistry(index, store, extraBuiltins)

    /** Default façade for the worker: real Postgres index over the blocks Db connection + on-disk jar store. */
    val registry: Registry by lazy { buildRegistry(BlockIndex { Db.connect() }, BlockStore()) }

    /** Exposed for CLI `list --no-db`: returns only the static builtins without touching the DB. */
    fun builtinContracts(): List<BlockContract> = (DispatchAssembly.builtinContracts + extraBuiltins).values.toList()

    /** Engine-neutral plan for the def: compile to IR (IR is load-bearing on execution). Emits the
     *  gate #3 marker proving execution went THROUGH the compiler. Bound into BoundWorkflow at startup.
     *  Existence-check is done by the compiler's resolve step; WorkflowValidator is no longer called here
     *  (it stays in :registry for its own unit tests). */
    fun planFor(def: WorkflowDef): WorkflowIR {
        val ir = WorkflowCompiler.compile(def, registry)
        // gate #3 negative evidence: prove the execution path went THROUGH the compiler/IR.
        println("[compiler] compiled ${ir.name} nodes=${ir.nodes.size} irHash=${WorkflowCompiler.canonical(ir).hashCode()}")
        return ir
    }

    /** ordered contracts for the def — for the CLI temporal target. Delegates to [planFor] (which carries
     *  the marker) and lowers the IR to the legacy ordered contract list. */
    fun contractsFor(def: WorkflowDef): List<BlockContract> = TemporalBackend.lower(planFor(def))
}
