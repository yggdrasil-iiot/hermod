// registry/src/test/kotlin/hermod/registry/WorkflowStoreTest.kt
package yggdrasil.hermod.registry

import yggdrasil.hermod.core.WorkflowDef
import yggdrasil.hermod.core.WorkflowStep
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowStoreTest {
    private lateinit var store: WorkflowStore

    @BeforeTest fun setup() {
        RegistryDbSupport.reset()                                  // ensures schema (whole registry-schema.sql) + truncates block_index
        RegistryDbSupport.connection().use { it.createStatement().execute("TRUNCATE workflow_def") }
        store = WorkflowStore(RegistryDbSupport::connection)
    }

    private fun def(name: String) = WorkflowDef(name, listOf(
        WorkflowStep("db.read", "1.0.0", id = "src", params = mapOf("table" to "source_rows")),
    ))

    @Test fun `save then get round-trips the def`() {
        assertTrue(store.save(def("wf"), "1.0.0").ok)
        val got = store.get("wf", "1.0.0")
        assertEquals("wf", got?.name)
        assertEquals("src", got?.steps?.first()?.id)
    }

    @Test fun `duplicate name+version is rejected (immutable)`() {
        assertTrue(store.save(def("wf"), "1.0.0").ok)
        val r = store.save(def("wf"), "1.0.0")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("immutable") || r.error!!.contains("exists"))
    }

    @Test fun `list and listDeployed reflect saved rows`() {
        store.save(def("a"), "1.0.0")
        store.save(def("b"), "2.0.0")
        assertEquals(2, store.list().size)
        assertEquals(setOf("a" to "1.0.0", "b" to "2.0.0"), store.listDeployed().toSet())
    }

    @Test fun `get missing returns null`() { assertNull(store.get("nope", "9.9.9")) }
}
