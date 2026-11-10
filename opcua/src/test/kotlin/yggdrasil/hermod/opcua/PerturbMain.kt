package yggdrasil.hermod.opcua

/**
 * Test/gate helper: write one Double value to the running sim out-of-band (simulates an ungoverned field
 * change / drift). NOT part of the governed path — it bypasses command-policy/EURange on purpose,
 * standing in for a local operator or rogue write.
 *
 * Usage: PerturbMain <nodeId> <value> [endpoint]   (endpoint defaults to HERMOD_OPCUA_URL or :48400)
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: PerturbMain <nodeId> <value> [endpoint]" }
    val nodeId = args[0]
    val value = args[1]
    val endpoint = args.getOrNull(2) ?: System.getenv("HERMOD_OPCUA_URL") ?: "opc.tcp://localhost:48400"

    OpcUaApplyPort(endpoint).connect().use { port ->
        val outcome = port.write(nodeId, "Double", value)
        println("[perturb] wrote $value to $nodeId -> ok=${outcome.ok} detail=${outcome.detail}")
        if (!outcome.ok) { System.err.println("[perturb] write not confirmed"); kotlin.system.exitProcess(1) }
    }
}
