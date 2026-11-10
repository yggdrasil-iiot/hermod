package yggdrasil.hermod.opcua

import java.io.File

/**
 * Gate helper (R4 FSM PoC): read the live equipment state, run the pure [TransitionGovernor], and print
 * the decision — NOTHING more. The bash gate acts on the decision (dispatch the governed run on ALLOW).
 * Keeps :opcua/src/main untouched and free of any HTTP client (cf. PerturbMain/EmitProbeMain).
 *
 * Usage: FsmGovernMain <fsmFile> <command> [endpoint]   (endpoint defaults to HERMOD_OPCUA_URL or :48400)
 * Prints exactly one line: `ALLOW <workflow>` or `DENY <reason>`. Exit 0 either way (the decision is data).
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: FsmGovernMain <fsmFile> <command> [endpoint]" }
    val command = args[1]
    val endpoint = args.getOrNull(2) ?: System.getenv("HERMOD_OPCUA_URL") ?: "opc.tcp://localhost:48400"

    // The decision is data: ANY failure (bad file, sim down, unreadable node) becomes a DENY line so the
    // gate always sees a decision and fails CLOSED — never a bare stack trace with no ALLOW/DENY.
    val decision: GovernDecision = try {
        val fsm = FsmSpec.fromFile(File(args[0]))
        // Resolve the logical stateNode key -> nodeId via the site model (classpath default or HERMOD_OPCUA_MODEL).
        val nodeId = SiteModel.default().node(fsm.stateNode).nodeId
        OpcUaApplyPort(endpoint).connect().use { port ->
            val rr = port.read(nodeId)
            val code = rr.value?.toDoubleOrNull()?.toInt()
            if (!rr.good || code == null) GovernDecision.Deny("could not read stateNode '$nodeId' (good=${rr.good}, value=${rr.value})")
            else TransitionGovernor.govern(fsm, code, command)
        }
    } catch (t: Throwable) {
        GovernDecision.Deny("govern failed: ${t.message ?: t.toString()}")
    }
    when (decision) {
        is GovernDecision.Allow -> println("ALLOW ${decision.workflow}")
        is GovernDecision.Deny  -> println("DENY ${decision.reason}")
    }
}
