package yggdrasil.hermod.opcua

import yggdrasil.hermod.sdk.BlockInput
import yggdrasil.hermod.sdk.PermanentBlockFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [OpcUaCallBlock] using [FakeApplyPort] — no Milo, no DB.
 */
class OpcUaCallBlockTest {

    private val model = SiteModel.fromClasspath()

    private fun block(fake: FakeApplyPort) = OpcUaCallBlock(apply = fake, model = model)

    @Test fun `forward confirms on rising edge`() {
        val fake = FakeApplyPort(callTimeout = false)
        val input = BlockInput(rows = listOf(mapOf("id" to "recipe.rpmSetpoint", "val" to "1500")))
        val out = block(fake).forward(input)
        // rows pass through unchanged
        assertEquals(input.rows, out.rows)
        // call was made to the model's activate command node
        assertEquals(1, fake.calls.size)
        assertEquals(model.activate.command.nodeId, fake.calls[0].first)
        assertEquals(model.activate.doneNode.nodeId, fake.calls[0].second)
    }

    @Test fun `forward fails closed when not confirmed (timeout)`() {
        val fake = FakeApplyPort(callTimeout = true)
        val input = BlockInput(rows = listOf(mapOf("id" to "recipe.rpmSetpoint", "val" to "1500")))
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
    }

    @Test fun `forward honors failAtBlockId injection`() {
        val fake = FakeApplyPort()
        val input = BlockInput(
            rows = listOf(mapOf("id" to "recipe.rpmSetpoint", "val" to "1500")),
            failAtBlockId = "opcua.call",
        )
        assertFailsWith<PermanentBlockFailure> { block(fake).forward(input) }
        // call must NOT have been made
        assertEquals(0, fake.calls.size)
    }

    @Test fun `forward passes the model's doneClear mode (not the default)`() {
        val fake = FakeApplyPort(callTimeout = false)
        val model = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { r.rpm: { nodeId: "ns=2;s=R/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean }, doneClear: master-clears }
        """.trimIndent())
        OpcUaCallBlock(apply = fake, model = model).forward(BlockInput(rows = emptyList()))
        assertEquals(yggdrasil.hermod.sdk.DoneClearMode.MASTER_CLEARS, fake.doneClears[0])
    }

    @Test fun `forward fails closed on an invalid model`() {
        val badModel = SiteModel.parse("""
            endpoint: "opc.tcp://x:1"
            nodes: { recipe.rpm: { nodeId: "Recipe/Rpm", type: Double } }
            activate: { command: { nodeId: "ns=2;s=R/Go", type: Method }, doneNode: { nodeId: "ns=2;s=R/Done", type: Boolean } }
        """.trimIndent())
        assertFailsWith<PermanentBlockFailure> {
            OpcUaCallBlock(apply = FakeApplyPort(), model = badModel)
                .forward(BlockInput(rows = emptyList()))
        }
    }
}
