package yggdrasil.hermod.opcua

/**
 * Reads the equipment's current state code for an FSM's stateNode. Encapsulates the SiteModel + OpcUaApplyPort
 * read so the yggdrasil.hermod.sdk.ReadResult type never crosses into :app (which has no :sdk compile dep). Returns null
 * on any read failure (bad node, sim down, unparseable/!good value). Mirrors FsmGovernMain's read idiom.
 */
object FsmStateReader {
    fun readStateCode(fsm: FsmSpec, endpoint: String): Int? =
        try {
            val nodeId = SiteModel.default().node(fsm.stateNode).nodeId
            OpcUaApplyPort(endpoint).connect().use { port ->
                val rr = port.read(nodeId)
                if (rr.good) rr.value?.toDoubleOrNull()?.toInt() else null
            }
        } catch (t: Throwable) { null }
}
