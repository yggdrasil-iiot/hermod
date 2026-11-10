package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*

/** Fake actuation: IRREVERSIBLE, requires human approval before. Records firing in a sink. */
class ActuateBlock(private val sink: MutableList<String> = FIRED) : Block {
    // WARNING: the default sink `FIRED` is process-global mutable state. Tests that construct
    // ActuateBlock() (e.g. via HandlerRegistry.default() in runtime) share it across cases —
    // call FIRED.clear() in test setup, or inject a local sink, to avoid cross-test leakage.
    companion object { val FIRED = mutableListOf<String>() }

    override val id = "actuate"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        sink.add("ACTUATED")
        return BlockOutput(rows = input.rows)
    }
}
