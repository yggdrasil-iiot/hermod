package yggdrasil.hermod.opcua.ncmd
import yggdrasil.hermod.sdk.DoneClearMode
import kotlin.test.*
class SparkplugNcmdApplyPortTest {
    private fun portWith(fake: FakeMqttTransport) =
        SparkplugNcmdApplyPort(fake, "Bifrost:Line1", "recipe-edge-cmd", deadlineMs = 2000, idgen = { "fixed-1" })

    @Test fun `write completes on the matching cmdId response`() {
        val fake = FakeMqttTransport()
        fake.onPublish { topic, bytes ->                       // echo a success response for the same cmdId
            val cmd = SpbCodec.decodeCommand(bytes)
            fake.deliver("bifrost/Bifrost:Line1/RESULT/recipe-edge-cmd",
                SpbCodec.encodeResponse(NcmdResponse(cmd.cmdId, true, detail="ok")))
        }
        val out = portWith(fake).write("ns=2;s=Recipe/Rpm", "Double", "1500")
        assertTrue(out.ok); assertEquals("ok", out.detail)
    }
    @Test fun `write times out when no response arrives`() {
        val fake = FakeMqttTransport()                          // no auto-response
        val out = SparkplugNcmdApplyPort(fake, "Bifrost:Line1", "recipe-edge-cmd", deadlineMs = 300)
            .write("ns=2;s=Recipe/Rpm", "Double", "1500")
        assertFalse(out.ok); assertTrue(out.detail.contains("timeout"))
    }
    @Test fun `a response for an unknown cmdId is dropped`() {
        val fake = FakeMqttTransport()
        val port = SparkplugNcmdApplyPort(fake, "Bifrost:Line1", "recipe-edge-cmd", deadlineMs = 300)
        // deliver a stray response BEFORE any request — must not crash
        fake.deliver("bifrost/Bifrost:Line1/RESULT/recipe-edge-cmd",
            SpbCodec.encodeResponse(NcmdResponse("nobody", true)))
        val out = port.write("ns=2;s=Recipe/Rpm","Double","1500")   // still times out
        assertFalse(out.ok)
    }
    @Test fun `read returns value+good from the response`() {
        val fake = FakeMqttTransport()
        fake.onPublish { _, bytes ->
            val cmd = SpbCodec.decodeCommand(bytes)
            fake.deliver("bifrost/Bifrost:Line1/RESULT/recipe-edge-cmd",
                SpbCodec.encodeResponse(NcmdResponse(cmd.cmdId, true, value="1500.0", good=true)))
        }
        val r = portWith(fake).read("ns=2;s=Recipe/Rpm")
        assertEquals("1500.0", r.value); assertTrue(r.good)
    }
    @Test fun `call fails closed and publishes nothing for a non-ON_RELEASE doneClear`() {
        val fake = FakeMqttTransport()
        val out = portWith(fake).call(
            "ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", 1000,
            DoneClearMode.EXPLICIT_RESET)
        assertFalse(out.ok)
        assertTrue(out.detail.contains("not implemented in the NCMD apply path"), "detail was: ${out.detail}")
        assertTrue(fake.published.isEmpty(), "must not publish any NCMD when failing closed")
    }

    @Test fun `call on ON_RELEASE publishes one call command and completes on the matching response`() {
        val fake = FakeMqttTransport()
        fake.onPublish { topic, bytes ->                       // echo a success response for the same cmdId
            val cmd = SpbCodec.decodeCommand(bytes)
            assertEquals("call", cmd.op)                       // it is the activate command
            fake.deliver("bifrost/Bifrost:Line1/RESULT/recipe-edge-cmd",
                SpbCodec.encodeResponse(NcmdResponse(cmd.cmdId, true, detail = "rising-edge confirmed")))
        }
        val out = portWith(fake).call(
            "ns=2;s=Recipe/ApplyRecipe", "ns=2;s=Recipe/ApplyDone", 1000,
            DoneClearMode.ON_RELEASE)
        assertTrue(out.ok, "detail was: ${out.detail}")
        assertEquals(1, fake.published.size)                   // exactly one publish (the call)
    }
}
