package yggdrasil.hermod.core
import kotlin.test.*
class IdempotencyKeyTest {
    @Test fun `row field expression extracts the field`() {
        assertEquals("A", IdempotencyKey.derive("row:id", mapOf("id" to "A", "val" to "x")))
    }
    @Test fun `missing field yields null`() {
        assertNull(IdempotencyKey.derive("row:id", mapOf("val" to "x")))
    }
    // Pins the intentional v0.2 contract: an unrecognized expression prefix yields null
    // (the v0.1 private deriveKey did a raw lookup; the shared API tightens this). Chunk 2's
    // registry validator relies on this contract.
    @Test fun `unknown prefix expression yields null`() {
        assertNull(IdempotencyKey.derive("custom:id", mapOf("custom:id" to "A")))
    }
}
