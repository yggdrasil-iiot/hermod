package yggdrasil.hermod.opcua.ncmd
import kotlin.test.*
class SpbCodecTest {
    @Test fun `command round-trips uuid, metric, and control properties`() {
        val cmd = NcmdCommand("c-1", "write", "ns=2;s=Recipe/Rpm", 1500.0, "Double")
        val back = SpbCodec.decodeCommand(SpbCodec.encodeCommand(cmd))
        assertEquals("c-1", back.cmdId); assertEquals("write", back.op)
        assertEquals("ns=2;s=Recipe/Rpm", back.name); assertEquals(1500.0, back.value)
        assertEquals("Double", back.type)
    }
    @Test fun `call command carries doneNode and timeout as properties`() {
        val cmd = NcmdCommand("c-2","call","ns=2;s=Recipe/ApplyRecipe", true,"Boolean",
            doneNode="ns=2;s=Recipe/ApplyDone", timeoutMs=30000)
        val back = SpbCodec.decodeCommand(SpbCodec.encodeCommand(cmd))
        assertEquals(true, back.value); assertEquals("ns=2;s=Recipe/ApplyDone", back.doneNode)
        assertEquals(30000L, back.timeoutMs)
    }
    @Test fun `response round-trips`() {
        val r = NcmdResponse("c-1", true, detail="written+confirmed")
        val back = SpbCodec.decodeResponse(SpbCodec.encodeResponse(r))
        assertEquals("c-1", back.cmdId); assertTrue(back.ok); assertEquals("written+confirmed", back.detail)
    }
    @Test fun `read response carries value+good`() {
        val r = NcmdResponse("c-3", true, value="1500.0", good=true)
        val back = SpbCodec.decodeResponse(SpbCodec.encodeResponse(r))
        assertEquals("1500.0", back.value); assertEquals(true, back.good)
    }
}
