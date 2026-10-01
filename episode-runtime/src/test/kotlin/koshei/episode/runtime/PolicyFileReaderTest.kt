package koshei.episode.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolicyFileReaderTest {
    @TempDir lateinit var dir: Path

    @Test fun `no file is MISSING - the table was taken down on purpose`() {
        assertEquals(PolicyReadResult("MISSING"), PolicyFileReader(dir.resolve("active.yaml")).read())
    }

    @Test fun `a readable file is FOUND as JSON text`() {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, "version: \"1\"\n")
        val read = PolicyFileReader(file).read()
        assertEquals("FOUND", read.kind)
        assertEquals("""{"version":"1"}""", read.json)
    }

    @Test fun `a repeated key is UNREADABLE and names the file`() {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, "agentLayerEnabled: false\nagentLayerEnabled: true\n")
        val read = PolicyFileReader(file).read()
        assertEquals("UNREADABLE", read.kind)
        assertTrue("agentLayerEnabled" in read.reason!! && "active.yaml" in read.reason!!, read.reason)
    }

    @Test fun `a directory in its place is UNREADABLE`() {
        val read = PolicyFileReader(Files.createDirectory(dir.resolve("active.yaml"))).read()
        assertEquals("UNREADABLE", read.kind)
        assertNotNull(read.reason)
    }

    @Test fun `the file is read afresh on every call`() {
        val file = dir.resolve("active.yaml")
        val reader = PolicyFileReader(file)
        Files.writeString(file, "version: \"1\"\n")
        assertEquals("""{"version":"1"}""", reader.read().json)
        Files.writeString(file, "version: \"2\"\n")
        assertEquals("""{"version":"2"}""", reader.read().json)
    }
}
