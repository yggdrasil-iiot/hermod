package koshei.runtime

import com.google.protobuf.ByteString
import io.temporal.api.common.v1.Payload
import kotlin.test.Test
import kotlin.test.assertEquals

class RawJsonConverterTest {
    private val converter = DataConverterSupport.dataConverter()

    @Test fun `raw JSON goes out and comes back byte for byte, duplicate keys and all`() {
        val text = """{"b":1,"a":null,"a":2}"""
        val payload = converter.toPayload(RawJson(text)).get()
        assertEquals("json/plain", payload.getMetadataOrThrow("encoding").toStringUtf8())
        assertEquals(text, payload.data.toStringUtf8())
        assertEquals(RawJson(text), converter.fromPayload(payload, RawJson::class.java, RawJson::class.java))
    }

    @Test fun `a payload another SDK wrote is read as it was written`() {
        val written = """{"outcome": "NO_GROUNDS", "candidateId": null}"""
        val python = Payload.newBuilder()
            .putMetadata("encoding", ByteString.copyFromUtf8("json/plain"))
            .setData(ByteString.copyFromUtf8(written))
            .build()
        assertEquals(written, converter.fromPayload(python, RawJson::class.java, RawJson::class.java).text)
    }

    @Test fun `everything else still goes through the Kotlin-aware Jackson converter`() {
        val payload = converter.toPayload(WorkflowInput(workflowName = "x")).get()
        assertEquals("x", converter.fromPayload(payload, WorkflowInput::class.java, WorkflowInput::class.java).workflowName)
    }
}
