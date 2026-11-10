package yggdrasil.hermod.opcua

import org.eclipse.milo.opcua.sdk.server.OpcUaServer
import org.eclipse.milo.opcua.sdk.server.api.DataItem
import org.eclipse.milo.opcua.sdk.server.api.ManagedNamespaceWithLifecycle
import org.eclipse.milo.opcua.sdk.server.api.MonitoredItem
import org.eclipse.milo.opcua.sdk.server.api.config.OpcUaServerConfig
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode
import org.eclipse.milo.opcua.stack.core.Identifiers
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.ubyte
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode
import org.eclipse.milo.opcua.stack.core.types.enumerated.UserTokenType
import org.eclipse.milo.opcua.stack.core.types.structured.UserTokenPolicy
import org.eclipse.milo.opcua.stack.server.EndpointConfiguration
import java.util.concurrent.TimeUnit

/**
 * In-JVM embedded Milo OPC-UA server — the fake D3/PLC endpoint for integration tests and the
 * standalone gate sim. All nodes are derived from the [SiteModel]; no node keys are hardcoded.
 *
 * The "write-trigger → done-bit" mechanism:
 *   - Writing `true` to the ApplyRecipe trigger node is the R1 backing for `ApplyPort.call`.
 *   - A background polling thread models rung logic: done follows the trigger — set true while the
 *     command is asserted, cleared (rearmed) when the master de-asserts the trigger. The master
 *     (`OpcUaApplyPort.call`, `ON_RELEASE`) owns the de-assert; the sim never auto-clears the trigger.
 *   - [reset] returns both to false so the next run sees a fresh false→true rising edge.
 */
class EmbeddedMiloSim(
    private val model: SiteModel = SiteModel.default(),
    private val bindPort: Int = 48400,
) : AutoCloseable {

    private lateinit var server: OpcUaServer
    private lateinit var namespace: HermodNamespace

    @Volatile private var running = false
    private var pollingThread: Thread? = null

    fun start(): EmbeddedMiloSim {
        val endpointConfig = EndpointConfiguration.newBuilder()
            .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
            .setBindAddress("localhost")
            .setBindPort(bindPort)
            .setHostname("localhost")
            .setPath("")
            .setSecurityPolicy(SecurityPolicy.None)
            .setSecurityMode(MessageSecurityMode.None)
            .addTokenPolicies(UserTokenPolicy("anonymous", UserTokenType.Anonymous, null, null, null))
            .build()

        val serverConfig = OpcUaServerConfig.builder()
            .setApplicationUri("urn:hermod:opcua:sim-server")
            .setApplicationName(LocalizedText.english("Hermod OPC-UA Sim"))
            .setEndpoints(setOf(endpointConfig))
            .build()

        server = OpcUaServer(serverConfig)
        // Pre-register the namespace URI so ManagedNamespaceWithLifecycle gets index 2
        // (index 0 = OPC-UA foundation, index 1 = server application URI)
        server.namespaceTable.addUri(HermodNamespace.NAMESPACE_URI)
        namespace = HermodNamespace(server, model)
        namespace.startup()
        server.startup().get(30, TimeUnit.SECONDS)

        running = true
        pollingThread = Thread {
            while (running) {
                try {
                    // Model rung logic: done follows the trigger. done=true while the command is asserted; done clears
                    // (rearms) when the master de-asserts the trigger — so the next activate sees a fresh false->true edge.
                    // The master (OpcUaApplyPort.call, ON_RELEASE) owns the trigger de-assert; the sim no longer auto-clears it.
                    val triggerVal = namespace.triggerNode?.value?.value?.value
                    namespace.doneNode?.value = DataValue(Variant(triggerVal == true))
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.also { it.isDaemon = true; it.start() }

        return this
    }

    /** Test helper: current value of a model node key, string-encoded (for assertions). */
    fun peek(modelKey: String): String? {
        val nodeDef = model.node(modelKey)
        return namespace.peekByNodeId(nodeDef.nodeId)
    }

    /** Reset done-bit + trigger so the next run sees a fresh rising edge. */
    fun reset() {
        namespace.doneNode?.value = DataValue(Variant(false))
        namespace.triggerNode?.value = DataValue(Variant(false))
    }

    /** Test-support (parallels [reset]/[peek]): assert the trigger server-side so the poll thread latches
     *  done — used to induce a stuck/aborted-prior-command state for the baseline-guard test. */
    fun pokeTrigger(value: Boolean) { namespace.triggerNode?.value = DataValue(Variant(value)) }

    override fun close() {
        running = false
        pollingThread?.interrupt()
        if (::server.isInitialized) {
            try { namespace.shutdown() } catch (_: Exception) {}
            try { server.shutdown().get(10, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
    }
}

// ---------------------------------------------------------------------------
// Internal namespace
// ---------------------------------------------------------------------------

internal class HermodNamespace(server: OpcUaServer, private val model: SiteModel) :
    ManagedNamespaceWithLifecycle(server, NAMESPACE_URI) {

    companion object {
        const val NAMESPACE_URI = "urn:hermod:opcua:sim"
    }

    /** OPC-UA nodeId string → UaVariableNode, for setpoint nodes. */
    private val setpointNodes = mutableMapOf<String, UaVariableNode>()

    // Exposed for the polling thread and reset().
    // Thread-safety: UaVariableNode.value is backed by Milo's AtomicReference<DataValue>, so
    // concurrent reads (client + polling thread + reset()) are safe without additional locking.
    var triggerNode: UaVariableNode? = null
        internal set
    var doneNode: UaVariableNode? = null
        internal set

    init {
        lifecycleManager.addStartupTask(::createNodes)
    }

    override fun onDataItemsCreated(items: List<DataItem>) = Unit
    override fun onDataItemsModified(items: List<DataItem>) = Unit
    override fun onDataItemsDeleted(items: List<DataItem>) = Unit
    override fun onMonitoringModeChanged(items: List<MonitoredItem>) = Unit

    private fun createNodes() {
        val nm = nodeManager

        val recipeFolder = UaFolderNode(
            nodeContext,
            newNodeId("Recipe"),
            newQualifiedName("Recipe"),
            LocalizedText.english("Recipe"),
        )
        nm.addNode(recipeFolder)

        // Register every setpoint from the model as a writable Double (initial 0.0).
        // Enumerated from model.setpoints() — NOT hardcoded.
        for ((_, nodeDef) in model.setpoints()) {
            val identifier = identifierOf(nodeDef.nodeId)   // e.g. "Recipe/Rpm"
            val browseName = identifier.substringAfterLast("/")
            val node = makeDoubleNode(identifier, browseName, 0.0)
            nm.addNode(node)
            setpointNodes[nodeDef.nodeId] = node
        }

        // Trigger node (ApplyRecipe): writable Boolean — client writes `true` to invoke
        val triggerDef = model.activate.command
        val triggerId = identifierOf(triggerDef.nodeId)
        val triggerBrowse = triggerId.substringAfterLast("/")
        triggerNode = makeBoolNode(triggerId, triggerBrowse, false)
        nm.addNode(triggerNode!!)

        // Done node (ApplyDone): Boolean, init false — set by polling thread on trigger
        val doneDef = model.activate.doneNode
        val doneId = identifierOf(doneDef.nodeId)
        val doneBrowse = doneId.substringAfterLast("/")
        doneNode = makeBoolNode(doneId, doneBrowse, false)
        nm.addNode(doneNode!!)
    }

    /** Return the string identifier from `ns=2;s=Recipe/Rpm` → `"Recipe/Rpm"`. */
    private fun identifierOf(nodeId: String): String = nodeId.substringAfter(";s=")

    fun peekByNodeId(nodeIdStr: String): String? {
        val node: UaVariableNode? = setpointNodes[nodeIdStr]
            ?: if (nodeIdStr == model.activate.doneNode.nodeId) doneNode
            else if (nodeIdStr == model.activate.command.nodeId) triggerNode
            else null
        return node?.value?.value?.value?.toString()
    }

    private fun makeDoubleNode(identifier: String, browseName: String, initial: Double): UaVariableNode =
        UaVariableNode.UaVariableNodeBuilder(nodeContext)
            .setNodeId(newNodeId(identifier))
            .setBrowseName(newQualifiedName(browseName))
            .setDisplayName(LocalizedText.english(browseName))
            .setDataType(Identifiers.Double)
            .setTypeDefinition(Identifiers.BaseDataVariableType)
            .setAccessLevel(ubyte(3))
            .setUserAccessLevel(ubyte(3))
            .build()
            .also { it.value = DataValue(Variant(initial)) }

    private fun makeBoolNode(identifier: String, browseName: String, initial: Boolean): UaVariableNode =
        UaVariableNode.UaVariableNodeBuilder(nodeContext)
            .setNodeId(newNodeId(identifier))
            .setBrowseName(newQualifiedName(browseName))
            .setDisplayName(LocalizedText.english(browseName))
            .setDataType(Identifiers.Boolean)
            .setTypeDefinition(Identifiers.BaseDataVariableType)
            .setAccessLevel(ubyte(3))
            .setUserAccessLevel(ubyte(3))
            .build()
            .also { it.value = DataValue(Variant(initial)) }
}
