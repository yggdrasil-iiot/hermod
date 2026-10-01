package koshei.episode.runtime

import koshei.episode.CandidateKind
import koshei.episode.PolicyParse
import koshei.episode.PolicyRead
import koshei.episode.parsePolicy
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

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

    @Test fun `a file over 1 MiB is UNREADABLE before it is read`() {
        val file = dir.resolve("active.yaml")
        val frame = "version: \"\"\n"
        Files.writeString(file, "version: \"" + "x".repeat(1024 * 1024 + 1 - frame.length) + "\"\n")
        assertEquals(1024L * 1024 + 1, Files.size(file))
        val read = PolicyFileReader(file).read()
        assertEquals("UNREADABLE", read.kind, read.json?.take(40))
        assertTrue("1048576" in read.reason!!, read.reason)
    }

    @Test fun `a table nested 100 deep is UNREADABLE`() {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, "a: " + "{b: ".repeat(100) + "1" + "}".repeat(100) + "\n")
        val read = PolicyFileReader(file).read()
        assertEquals("UNREADABLE", read.kind, read.json?.take(40))
        assertTrue("depth" in read.reason!!, read.reason)
    }

    @Test fun `200 000 open flow sequences are refused within a second`() = refusedQuickly("[".repeat(200_000))

    @Test fun `200 000 nested block sequences are refused within a second`() = refusedQuickly("- ".repeat(200_000) + "1\n")

    /** The pre-pass must stop at the depth limit: readPolicy is a local activity, and a timeout does not stop the thread. */
    private fun refusedQuickly(text: String) {
        val file = dir.resolve("active.yaml")
        Files.writeString(file, text)
        assertTrue(Files.size(file) <= PolicyFileReader.MAX_BYTES, "under the size limit, so the parser sees it")
        val read = assertTimeoutPreemptively(Duration.ofSeconds(1)) { PolicyFileReader(file).read() }
        assertEquals("UNREADABLE", read.kind, read.json?.take(40))
        assertTrue("depth" in read.reason!!, read.reason)
    }

    @Test fun `an empty expiresAt in the committed table is null, and the table is still valid`() {
        val committed = Files.readString(Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml"))
        // Not tied to a line ending: with core.autocrlf the checked-out file may end its lines with CRLF.
        assertEquals(1, Regex("expiresAt: null").findAll(committed).count(), "the committed table spells out its null once")
        val file = dir.resolve("active.yaml")
        Files.writeString(file, committed.replace("expiresAt: null", "expiresAt:"))
        val read = PolicyFileReader(file).read()
        assertEquals("FOUND", read.kind, read.reason)
        val parsed = parsePolicy(read.toRead().let { (it as PolicyRead.Found).node })
        assertTrue(parsed is PolicyParse.Valid, "an empty value is null, as YAML says: $parsed")
    }

    @Test fun `the file is read afresh on every call`() {
        val file = dir.resolve("active.yaml")
        val reader = PolicyFileReader(file)
        Files.writeString(file, "version: \"1\"\n")
        assertEquals("""{"version":"1"}""", reader.read().json)
        Files.writeString(file, "version: \"2\"\n")
        assertEquals("""{"version":"2"}""", reader.read().json)
    }

    @Test fun `the committed v1 table is valid and keeps auto-approval off`() {
        val file = Path.of(System.getProperty("koshei.repoRoot"), "model", "episode-policy", "active.yaml")
        val read = PolicyFileReader(file).read()
        assertEquals("FOUND", read.kind, read.reason)
        val parsed = parsePolicy(read.toRead().let { (it as PolicyRead.Found).node })
        val table = (parsed as? PolicyParse.Valid)?.policy ?: fail("invalid: $parsed")
        assertTrue(table.agentLayerEnabled)
        assertEquals(false, table.autoApprove[CandidateKind.APPROVE_REMEDY]?.allowed, "policy v1 (design §10.1, §18)")
    }
}
