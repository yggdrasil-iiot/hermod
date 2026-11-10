package yggdrasil.hermod.delegation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegationPolicyValidatorTest {
    @Test fun `shipped canonical policy validates`() {
        assertTrue(DelegationPolicyValidator.validate(DelegationPolicy.default()).ok)
    }
    @Test fun `allowed endpoint without url is rejected`() {
        val p = DelegationPolicy.parse("""{ "default":"deny","endpoints":[{"id":"a","metric":"m","threshold":0.5,"allow":true}] }""")
        val r = DelegationPolicyValidator.validate(p)
        assertFalse(r.ok); assertTrue(r.errors.any { it.contains("url") })
    }
    @Test fun `out-of-range threshold is rejected`() {
        val p = DelegationPolicy.parse("""{ "default":"deny","endpoints":[{"id":"a","url":"http://x","metric":"m","threshold":1.5,"allow":true}] }""")
        assertTrue(DelegationPolicyValidator.validate(p).errors.any { it.contains("threshold") })
    }
    @Test fun `duplicate endpoint id is rejected`() {
        val p = DelegationPolicy.parse("""{ "default":"deny","endpoints":[
            {"id":"a","url":"http://x","metric":"m","threshold":0.5,"allow":true},
            {"id":"a","url":"http://y","metric":"m","threshold":0.5,"allow":true}] }""")
        assertTrue(DelegationPolicyValidator.validate(p).errors.any { it.contains("duplicate") })
    }
}
