package yggdrasil.hermod.delegation

import yggdrasil.hermod.sdk.DelegationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddedScoringSimTest {
    @Test fun `sim scores val deterministically and HttpDelegatePort parses it`() {
        val sim = EmbeddedScoringSim(port = 0).start()   // port 0 = ephemeral; read back the bound port
        try {
            val policy = DelegationPolicy.parse(
                """{ "default":"deny","endpoints":[{"id":"quality-scorer","url":"http://localhost:${sim.listenPort}/analytics/score","metric":"qualityScore","threshold":0.8,"allow":true}] }"""
            )
            val port = HttpDelegatePort(policy)
            val pass = port.call(DelegationRequest("quality-scorer", mapOf("id" to "recipe.rpmSetpoint", "val" to "300")))
            assertTrue(pass.ok); assertEquals(0.9, pass.score!!, 1e-9)   // 1 - 300/3000
            val rej = port.call(DelegationRequest("quality-scorer", mapOf("val" to "2900")))
            assertTrue(rej.score!! < 0.8)                                // 1 - 2900/3000 ≈ 0.033
        } finally { sim.close() }
    }
}
