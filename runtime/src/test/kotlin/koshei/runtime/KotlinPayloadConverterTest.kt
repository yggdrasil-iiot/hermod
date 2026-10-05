package koshei.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class KotlinPayloadConverterTest {
    private val converter = DataConverterSupport.dataConverter()

    @Test fun `a Kotlin data class goes through the Kotlin-aware Jackson converter`() {
        val payload = converter.toPayload(WorkflowInput(workflowName = "x")).get()
        assertEquals("x", converter.fromPayload(payload, WorkflowInput::class.java, WorkflowInput::class.java).workflowName)
    }
}
