package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SoakSupervisorTest {
    private lateinit var store: FsmDeploymentStore
    // Mirror FsmDeploymentStoreTest.setup() EXACTLY (real Postgres via RegistryDbSupport).
    @BeforeTest fun setup() {
        RegistryDbSupport.reset()
        RegistryDbSupport.connection().use { it.createStatement().execute("TRUNCATE fsm_deployment, fsm_deployment_audit") }
        store = FsmDeploymentStore(RegistryDbSupport::connection)
    }

    @Test fun `breach rolls back`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 2)
        store.recordFailure("u"); store.recordFailure("u")
        val actions = SoakSupervisor.sweep(store, java.time.Instant.now())
        assertEquals(1, actions.size)
        assertTrue(actions[0] is SoakSupervisor.RolledBack)
        assertEquals("v1", store.activeVersion("u"))
    }
    @Test fun `window elapsed promotes`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 1, failThreshold = 5)
        val future = java.time.Instant.now().plusSeconds(10)
        val actions = SoakSupervisor.sweep(store, future)
        assertTrue(actions.single() is SoakSupervisor.Promoted)
        assertTrue(store.soaking().none { it.unit == "u" })
    }
    @Test fun `still in window and under threshold is a no-op`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 3600, failThreshold = 2)
        store.recordFailure("u")
        assertTrue(SoakSupervisor.sweep(store, java.time.Instant.now()).isEmpty())
        assertEquals("v2", store.activeVersion("u"))
    }
    @Test fun `breach beats window-elapsed`() {
        store.deploy("u", "v1"); store.deploy("u", "v2", soakSeconds = 1, failThreshold = 1)
        store.recordFailure("u")
        val actions = SoakSupervisor.sweep(store, java.time.Instant.now().plusSeconds(10))
        assertTrue(actions.single() is SoakSupervisor.RolledBack)
    }
}
