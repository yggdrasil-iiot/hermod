package yggdrasil.hermod.registry

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlockIndexTest {
    private val index = BlockIndex(RegistryDbSupport::connection)

    @BeforeTest fun setup() = RegistryDbSupport.reset()

    private fun row(id: String = "io.example.x", version: String = "1.0.0") =
        BlockIndex.Row(id, version, """{"id":"$id"}""", "/store/$id/$version/abc.jar", "abc")

    @Test fun `insert then find by id and version returns the row`() {
        val r = row()
        index.insert(r)
        assertEquals(r, index.find("io.example.x", "1.0.0"))
    }

    @Test fun `find missing returns null`() {
        assertNull(index.find("nope", "9.9.9"))
    }

    @Test fun `duplicate primary key insert throws`() {
        index.insert(row())
        assertFailsWith<Exception> { index.insert(row()) }
    }

    @Test fun `list returns all inserted rows`() {
        index.insert(row(id = "a"))
        index.insert(row(id = "b"))
        assertEquals(2, index.list().size)
    }

    @Test fun `deprecate flips the flag and is visible in find and list`() {
        val idx = BlockIndex(RegistryDbSupport::connection)
        idx.insert(BlockIndex.Row("d.x", "1.0.0", "{}", "/tmp/x.jar", "sha"))
        assertFalse(idx.find("d.x", "1.0.0")!!.deprecated)
        idx.deprecate("d.x", "1.0.0")
        assertTrue(idx.find("d.x", "1.0.0")!!.deprecated)
        assertTrue(idx.list().first { it.id == "d.x" }.deprecated)
    }
}
