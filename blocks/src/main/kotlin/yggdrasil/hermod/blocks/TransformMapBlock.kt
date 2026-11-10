package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*

class TransformMapBlock : Block {
    override val id = "transform.map"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        if (input.slowMs > 0) Thread.sleep(input.slowMs) // test-only: widen in-flight window so parallel branches overlap (v0.3b concurrency proof)
        val mapped = input.rows.map { it + ("val" to (it["val"]?.uppercase())) }
        return BlockOutput(rows = mapped)
    }
}
