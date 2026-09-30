package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotTest {
    private val manifest = PicassoRun1.manifest

    @Test fun `lines stay verbatim, field order included`() {
        val line = PicassoRun1.incident("incident-6")
        val s = Snapshot.parse(manifest, listOf(line), emptyList())
        val out = s.toJson().get("incidents").get(0)
        val original = mapper.readTree(line)
        assertEquals(original, out)
        assertEquals(original.fieldNames().asSequence().toList(), out.fieldNames().asSequence().toList())
    }

    @Test fun `manifest keeps every field and schemaVersion stays the string 5`() {
        val m = PicassoRun1.snapshot(searchIds = listOf("search-1")).toJson().get("manifest")
        assertEquals(mapper.readTree(manifest), m)
        assertTrue(m.get("schemaVersion").isTextual)
    }

    @Test fun `a numeric schemaVersion is rejected`() {
        val numeric = manifest.replace("\"schemaVersion\":\"5\"", "\"schemaVersion\":5")
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(numeric, emptyList(), listOf(PicassoRun1.search("search-1"))) }
    }

    @Test fun `at least one line is required`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), emptyList()) }
    }

    @Test fun `an incident line needs its digest and a search line its searchId`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, listOf("""{"incidentId":"x"}"""), emptyList()) }
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("""{"outcome":"FOUND"}""")) }
    }

    @Test fun `a line that is not an object is rejected`() {
        assertFailsWith<IllegalArgumentException> { Snapshot.parse(manifest, emptyList(), listOf("[1,2]")) }
    }
}
