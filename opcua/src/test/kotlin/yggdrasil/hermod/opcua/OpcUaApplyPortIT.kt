package yggdrasil.hermod.opcua

import kotlin.test.*

/**
 * In-JVM loopback integration test: real Milo embedded server + Milo client over OPC-UA TCP.
 *
 * Covers:
 *   - [OpcUaApplyPort.write] + numeric read-back confirm
 *   - [OpcUaApplyPort.read] (prior-value capture pattern)
 *   - [OpcUaApplyPort.call] rising-edge confirm via [EmbeddedMiloSim]'s polling thread
 *   - [OpcUaApplyPort.call] timeout when the done node never flips to `true`
 *
 * Note: the read-back-mismatch failure path is NOT exercised here (impractical to induce on a
 * healthy loopback sim); it is covered by the Chunk-3 FakeApplyPort block test.
 */
class OpcUaApplyPortIT {

    private lateinit var sim: EmbeddedMiloSim
    private lateinit var port: OpcUaApplyPort
    private val model = SiteModel.default()

    @BeforeTest
    fun up() {
        sim = EmbeddedMiloSim(bindPort = 48401).start()
        port = OpcUaApplyPort("opc.tcp://localhost:48401").connect()
    }

    @AfterTest
    fun down() {
        // Null-safe: a partial up() (e.g. server started but client connect failed) must not leak
        if (::port.isInitialized) port.close()
        if (::sim.isInitialized) sim.close()
    }

    @Test
    fun `write then read-back confirms`() {
        val out = port.write("ns=2;s=Recipe/Rpm", "Double", "1500")
        assertTrue(out.ok, out.detail)
        // Read-back value is "1500.0" (Double.toString); normalise to int for assertion
        assertEquals("1500", port.read("ns=2;s=Recipe/Rpm").value?.let { it.toDouble().toInt().toString() })
    }

    @Test
    fun `read captures prior value`() {
        port.write("ns=2;s=Recipe/Temp", "Double", "200")
        val r = port.read("ns=2;s=Recipe/Temp")
        assertTrue(r.good)
        assertEquals("200", r.value?.toDoubleOrNull()?.toInt()?.toString())
    }

    @Test
    fun `call confirms on rising-edge done bit`() {
        sim.reset()   // ensure ApplyDone=false, trigger=false before the run
        val out = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", timeoutMs = 5000)
        assertTrue(out.ok, out.detail)
    }

    private fun awaitRead(nodeId: String, want: String, ms: Long = 3000) {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (port.read(nodeId).value?.equals(want, ignoreCase = true) == true) return
            Thread.sleep(50)
        }
        fail("node $nodeId never reached '$want' within ${ms}ms (last=${port.read(nodeId).value})")
    }

    @Test
    fun `call rejects a stale already-true done bit (no rising edge)`() {
        sim.reset()
        // Induce a stuck/aborted prior command: assert the trigger directly; the poll thread latches done=true.
        sim.pokeTrigger(true)
        awaitRead("ns=2;s=Recipe/ApplyDone", "true")   // wait out the ~50ms poll propagation
        // Baseline done=true -> no false->true edge -> must fail closed (not "is true now").
        val out = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", timeoutMs = 800)
        assertFalse(out.ok, "stale done=true must fail the baseline guard")
    }

    @Test
    fun `on-release de-asserts the trigger and rearms done for the next call`() {
        sim.reset()
        val first = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", timeoutMs = 5000)
        assertTrue(first.ok, first.detail)
        // The de-assert reached the wire: trigger false, and the sim rearms done false (await the poll tick).
        awaitRead("ns=2;s=Recipe/ApplyRecipe", "false")
        awaitRead("ns=2;s=Recipe/ApplyDone", "false")
        // A SECOND activate confirms with NO manual reset — the whole point of the fix.
        val second = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", timeoutMs = 5000)
        assertTrue(second.ok, second.detail)
    }

    @Test
    fun `unimplemented doneClear modes fail closed`() {
        sim.reset()
        val out = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", timeoutMs = 800,
            doneClear = yggdrasil.hermod.sdk.DoneClearMode.EXPLICIT_RESET)
        assertFalse(out.ok)
        assertTrue(out.detail.contains("not implemented"), out.detail)
    }

    @Test
    fun `call times out when done never rises`() {
        // Point doneNodeId at a Double node (Recipe/Temp, reads "0.0") whose value never equals
        // "true" → the rising-edge predicate never holds → timeout, not confirmed.
        val out = port.call("ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/Temp", timeoutMs = 800)
        assertFalse(out.ok)
    }
}
