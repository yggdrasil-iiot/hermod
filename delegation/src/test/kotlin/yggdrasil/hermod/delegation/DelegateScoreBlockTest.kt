package yggdrasil.hermod.delegation

import yggdrasil.hermod.sdk.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DelegateScoreBlockTest {
    private class FakeDelegate(val result: DelegationResult) : DelegatePort {
        var calls = 0
        override fun call(req: DelegationRequest): DelegationResult { calls++; return result }
    }

    private val policy = DelegationPolicy.parse(
        """{ "default":"deny","endpoints":[{"id":"quality-scorer","url":"http://x","metric":"qualityScore","threshold":0.8,"allow":true}] }"""
    )
    private fun input(endpoint: String?, vararg rows: Record) =
        BlockInput(rows = rows.toList(), params = endpoint?.let { mapOf("endpoint" to it) } ?: emptyMap())

    @Test fun `pass returns pass-through rows and binds score`() {
        val fake = FakeDelegate(DelegationResult(true, 0.90, "{}", "ok"))
        val out = DelegateScoreBlock(fake, policy).forward(input("quality-scorer", mapOf("id" to "recipe.rpmSetpoint", "val" to "300")))
        assertEquals(1, out.rows.size)
        assertEquals("0.9", out.boundState["score"])
        assertEquals(1, fake.calls)
    }

    @Test fun `sub-threshold score fails closed`() {
        val fake = FakeDelegate(DelegationResult(true, 0.30, "{}", "ok"))
        val ex = assertFailsWith<PermanentBlockFailure> {
            DelegateScoreBlock(fake, policy).forward(input("quality-scorer", mapOf("id" to "x", "val" to "2900")))
        }
        assertTrue(ex.message!!.contains("REJECTED"))
    }

    @Test fun `unlisted endpoint is denied and makes no call`() {
        val fake = FakeDelegate(DelegationResult(true, 0.99, "{}", "ok"))
        assertFailsWith<PermanentBlockFailure> {
            DelegateScoreBlock(fake, policy).forward(input("rogue-scorer", mapOf("id" to "x", "val" to "300")))
        }
        assertEquals(0, fake.calls)   // deny-by-default: the external service is never hit
    }

    @Test fun `service error fails closed`() {
        val fake = FakeDelegate(DelegationResult(false, null, "", "connection refused"))
        assertFailsWith<PermanentBlockFailure> {
            DelegateScoreBlock(fake, policy).forward(input("quality-scorer", mapOf("id" to "x", "val" to "300")))
        }
    }

    @Test fun `missing endpoint param fails closed`() {
        val fake = FakeDelegate(DelegationResult(true, 0.99, "{}", "ok"))
        assertFailsWith<PermanentBlockFailure> {
            DelegateScoreBlock(fake, policy).forward(input(null, mapOf("id" to "x", "val" to "300")))
        }
    }

    @Test fun `compensate is a NOOP`() {
        val action = DelegateScoreBlock(FakeDelegate(DelegationResult(true, 0.9, "{}", "ok")), policy)
            .compensate(emptyMap(), CompensationContext())
        assertEquals("NOOP", action.kind)
    }
}
