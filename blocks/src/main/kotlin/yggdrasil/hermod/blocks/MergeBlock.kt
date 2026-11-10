package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.Block
import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.BlockOutput
import yggdrasil.hermod.sdk.PermanentBlockFailure
import yggdrasil.hermod.sdk.Record

/**
 * First multi-input builtin: deterministic concat of two named inputs (left then right).
 * Pure transform (no side effect; compensation kind NONE) — never pushed to the comp stack.
 * Emits a gate marker so a cross-process gate can prove fan-in (both inputs consumed). v0.3a spec §5.2.
 */
class MergeBlock : Block {
    override val id = "merge"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        val left: List<Record> = input.namedInputs["left"] ?: emptyList()
        val right: List<Record> = input.namedInputs["right"] ?: emptyList()
        val out = left + right
        println("[merge] left=${left.size} right=${right.size} out=${out.size}")
        return BlockOutput(rows = out)
    }
}
