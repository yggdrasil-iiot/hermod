package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*

/** Fake email: appends to an in-memory sink. CONTEXTUAL compensation: correct if sent, else NOOP. */
class NotifyEmailBlock(private val sink: MutableList<String> = SENT) : Block {
    // WARNING: the default sink `SENT` is process-global mutable state. Tests that construct
    // NotifyEmailBlock() (e.g. via HandlerRegistry.default() in runtime) share it across cases —
    // call SENT.clear() in test setup, or inject a local sink, to avoid cross-test leakage.
    companion object { val SENT = mutableListOf<String>() }

    override val id = "notify.email"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        sink.add("SENT:${input.rows.joinToString { it["id"] ?: "?" }}")
        return BlockOutput(rows = input.rows, boundState = mapOf("sent" to "true"))
    }

    override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction =
        if (boundState["sent"] == "true") {
            sink.add("CORRECTION")
            CompensationAction("CORRECT", "correction notice sent")
        } else CompensationAction("NOOP", "nothing was sent")
}
