package io.example

import yggdrasil.hermod.sdk.Block
import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.BlockOutput

/**
 * External example plugin block. Built against `yggdrasil.hermod:sdk` from mavenLocal (compileOnly); the host
 * provides the sdk at runtime, so the plugin jar carries ONLY io.example.* — proving the §15 v0.2 gate:
 * an outside engineer's jar is published, resolved, isolation-loaded (child URLClassLoader), and run.
 *
 * Pure transform: passes rows through and stamps each with greeted=true (a marker the gate can observe).
 */
class GreetBlock : Block {                          // no-arg ctor (required by PluginLoader)
    override val id = "io.example.greet"
    override fun forward(input: BlockInput): BlockOutput {
        println("[plugin] forward io.example.greet")   // gate marker — ONLY the plugin path prints this
        return BlockOutput(rows = input.rows.map { it + ("greeted" to "true") })
    }
}
