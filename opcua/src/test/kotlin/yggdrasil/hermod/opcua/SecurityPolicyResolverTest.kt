package yggdrasil.hermod.opcua

import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import kotlin.test.*

class SecurityPolicyResolverTest {
    @Test fun `none resolves to SecurityPolicy None (case-insensitive)`() {
        assertEquals(SecurityPolicy.None, OpcUaApplyPort.securityPolicyFor("none"))
        assertEquals(SecurityPolicy.None, OpcUaApplyPort.securityPolicyFor("NONE"))
    }

    @Test fun `unknown policy fails closed`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            OpcUaApplyPort.securityPolicyFor("Basic256Sha256")
        }
        assertTrue(ex.message!!.contains("not implemented"), "message must name the unimplemented policy: ${ex.message}")
    }
}
