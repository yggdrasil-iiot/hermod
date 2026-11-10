package yggdrasil.hermod.opcua

import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.CompensationContext
import yggdrasil.hermod.sdk.PermanentBlockFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [OpcUaWriteBlock] using [FakeApplyPort] — no Milo, no DB.
 * CommandAudit writes are best-effort: with no Postgres reachable, `tryAudit` swallows the SQLException,
 * so these tests exercise the block logic without a DB (no audit-injection seam is needed).
 */
class OpcUaWriteBlockTest {

    private val model = SiteModel.fromClasspath()
    private val policy = CommandPolicy.fromClasspath()

    /** Build a block wired to a fake apply port and a no-op audit. */
    private fun block(fake: FakeApplyPort) = OpcUaWriteBlock(apply = fake, model = model, policy = policy)

    @Test fun `forward writes setpoints and captures prior into boundState`() {
        val fake = FakeApplyPort()
        // seed priors so read() returns something before we write
        fake.seed("ns=2;s=Recipe/Rpm", "0")
        fake.seed("ns=2;s=Recipe/Temp", "0")

        val input = BlockInput(rows = listOf(
            mapOf("id" to "recipe.rpmSetpoint", "val" to "1500"),
        ))
        val out = block(fake).forward(input)

        // The fake now holds the written value
        assertEquals("1500", fake.get("ns=2;s=Recipe/Rpm"))
        // boundState carries prior
        val prior = assertNotNull(out.boundState["priorSetpoints"])
        assertTrue(prior.contains("recipe.rpmSetpoint"), "prior must contain logical key")
        assertTrue(prior.contains("0"), "prior must contain the pre-write value 0")
        // rows pass through
        assertEquals(input.rows, out.rows)
    }

    @Test fun `forward denies an unauthorized node`() {
        val fake = FakeApplyPort()
        val input = BlockInput(rows = listOf(
            mapOf("id" to "recipe.secretValve", "val" to "1"),
        ))
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
        // fake must be untouched
        assertEquals(null, fake.get("ns=2;s=Recipe/Rpm"))
    }

    @Test fun `forward rejects out-of-EURange value`() {
        val fake = FakeApplyPort()
        fake.seed("ns=2;s=Recipe/Rpm", "0")
        val input = BlockInput(rows = listOf(
            mapOf("id" to "recipe.rpmSetpoint", "val" to "9999"),  // model high = 3000
        ))
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
        // value must NOT have been written
        assertEquals("0", fake.get("ns=2;s=Recipe/Rpm"))
    }

    @Test fun `forward fails closed on read-back mismatch`() {
        val fake = FakeApplyPort(readBackMismatch = true)
        fake.seed("ns=2;s=Recipe/Rpm", "0")
        val input = BlockInput(rows = listOf(
            mapOf("id" to "recipe.rpmSetpoint", "val" to "1500"),
        ))
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
    }

    @Test fun `forward honors failAtBlockId injection`() {
        val fake = FakeApplyPort()
        val input = BlockInput(
            rows = listOf(mapOf("id" to "recipe.rpmSetpoint", "val" to "1500")),
            failAtBlockId = "opcua.write",
        )
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
    }

    @Test fun `forward fails closed on an invalid model`() {
        val badModel = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "ns=2;s=R/Rpm", type: Int } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        val block = OpcUaWriteBlock(apply = FakeApplyPort(), model = badModel, policy = CommandPolicy.fromClasspath())
        assertFailsWith<PermanentBlockFailure> {
            block.forward(BlockInput(rows = listOf(mapOf("id" to "recipe.rpm", "val" to "10"))))
        }
    }

    @Test fun `compensate RESTORES prior setpoints`() {
        val fake = FakeApplyPort()
        fake.seed("ns=2;s=Recipe/Rpm", "0")
        fake.seed("ns=2;s=Recipe/Temp", "0")

        val input = BlockInput(rows = listOf(
            mapOf("id" to "recipe.rpmSetpoint", "val" to "1500"),
            mapOf("id" to "recipe.tempSetpoint", "val" to "200"),
        ))
        val out = block(fake).forward(input)
        assertEquals("1500", fake.get("ns=2;s=Recipe/Rpm"))
        assertEquals("200", fake.get("ns=2;s=Recipe/Temp"))

        // Now compensate
        val b = block(fake)
        val action = b.compensate(out.boundState, CompensationContext())
        assertEquals("RESTORE", action.kind)
        assertEquals("restored 2 setpoint(s)", action.detail)
        // Values must have been restored to "0"
        assertEquals("0", fake.get("ns=2;s=Recipe/Rpm"))
        assertEquals("0", fake.get("ns=2;s=Recipe/Temp"))
    }

    @Test fun `compensate audits an unconfirmed reverse write honestly`() {
        // A fake whose write() always returns ok=false (e.g. NCMD edge-deny / timeout).
        val fake = FakeApplyPort(readBackMismatch = true)
        val boundState = mapOf(
            "priorSetpoints" to OpcuaJson.write(mapOf("recipe.rpmSetpoint" to "1500")),
        )
        val action = block(fake).compensate(boundState, CompensationContext())
        assertEquals("RESTORE", action.kind)
        // The reverse write was NOT confirmed — the ledger entry must not claim it was restored.
        assertTrue(
            action.detail.contains("not confirmed"),
            "unconfirmed RESTORE must be flagged, was: ${action.detail}",
        )
        assertTrue(
            !action.detail.contains("restored 1 setpoint(s)") || action.detail.contains("restored 0"),
            "must not claim the failed reverse write was restored, was: ${action.detail}",
        )
    }
}
