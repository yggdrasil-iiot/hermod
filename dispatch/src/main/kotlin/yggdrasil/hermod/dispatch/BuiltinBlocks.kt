package yggdrasil.hermod.dispatch

import yggdrasil.hermod.blocks.*
import yggdrasil.hermod.delegation.DelegateScoreBlock
import yggdrasil.hermod.opcua.OpcUaCallBlock
import yggdrasil.hermod.opcua.OpcUaWriteBlock
import yggdrasil.hermod.sdk.Block

/** The static built-in handler set (REF v0.1 `HandlerRegistry.default()`); resolved without the DB. */
object BuiltinBlocks {
    // OpcUaWriteBlock() and OpcUaCallBlock() use lazy `effectiveApply` fields: constructing them here
    // does NOT connect to a live OPC-UA server. Connection is deferred to first forward() call.
    val instances: List<Block> = listOf(
        DbReadBlock(), TransformMapBlock(), DbUpsertBlock(), NotifyEmailBlock(), ActuateBlock(), MergeBlock(),
        OpcUaWriteBlock(), OpcUaCallBlock(),
        DelegateScoreBlock(),
    )
    val byId: Map<String, Block> = instances.associateBy { it.id }
}
