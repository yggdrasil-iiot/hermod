package yggdrasil.hermod.opcua

import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import kotlin.test.*

class IdentityProviderResolverTest {
    @Test fun `unset credentials resolve to anonymous`() {
        assertTrue(OpcUaApplyPort.identityProviderFor(null, null) is AnonymousProvider)
        assertTrue(OpcUaApplyPort.identityProviderFor("", "x") is AnonymousProvider, "blank user -> anonymous")
        assertTrue(OpcUaApplyPort.identityProviderFor("   ", "x") is AnonymousProvider, "whitespace user -> anonymous")
    }

    @Test fun `user plus pass resolves to username identity`() {
        assertTrue(OpcUaApplyPort.identityProviderFor("opcuauser", "password") is UsernameProvider)
    }
}
