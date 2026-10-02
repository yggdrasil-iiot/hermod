package koshei.episode.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundlesTest {
    @TempDir lateinit var dir: Path
    private val files = listOf(BundleFile("a.jsonl", "a", "aId"), BundleFile("b.jsonl", "b", "bId"))

    private fun manifest(a: Int, b: Int, runId: String? = "run-1", schema: String = "5") =
        Files.writeString(dir.resolve("manifest.json"),
            """{"schemaVersion":"$schema",${runId?.let { "\"runId\":\"$it\"," } ?: ""}"counts":{"a":$a,"b":$b}}""")

    private fun lines(name: String, vararg lines: String) = Files.writeString(dir.resolve(name), lines.joinToString("\n", postfix = "\n"))

    private fun read(unique: Boolean = true, readText: (Path) -> String = Files::readString) =
        Bundles.read(dir, "runId", setOf("5"), files, unique, readText)

    @Test fun `nothing is read before the manifest is there`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        assertIs<BundleRead.NotReady>(read())
    }

    @Test fun `lines are bounded by the manifest's counts - later lines belong to the next snapshot`() {
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-2"}""", """{"aId":"a-3"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        val r = assertIs<BundleRead.Ready>(read())
        assertEquals("run-1", r.runId)
        assertEquals(listOf("a-1", "a-2"), r.lines.getValue("a.jsonl").map { it.id })
        assertEquals(listOf(1, 2), r.lines.getValue("a.jsonl").map { it.position })
        assertEquals("""{"aId":"a-2"}""", r.lines.getValue("a.jsonl")[1].text)
        assertEquals(emptyList(), r.lines.getValue("b.jsonl"))
    }

    @Test fun `a file absent with a zero count is empty, absent with lines counted is broken`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        manifest(a = 1, b = 0)
        assertIs<BundleRead.Ready>(read())
        manifest(a = 1, b = 1)
        val r = assertIs<BundleRead.Broken>(read())
        assertTrue("b.jsonl" in r.why, r.why)
    }

    @Test fun `fewer lines than counted is not ready yet`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        assertIs<BundleRead.NotReady>(read())
    }

    @Test fun `a manifest that changed while the lines were read is read again later`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        var manifestReads = 0
        val r = read(readText = { p ->
            val text = Files.readString(p)
            if (p.fileName.toString() == "manifest.json" && ++manifestReads == 2) text.replace("run-1", "run-2") else text
        })
        assertIs<BundleRead.NotReady>(r)
    }

    @Test fun `what cannot be read is broken, never skipped`() {
        lines("b.jsonl")
        fun broken(a: String, why: String) {
            lines("a.jsonl", a)
            manifest(a = 1, b = 0)
            val r = read()
            assertIs<BundleRead.Broken>(r, a)
            assertTrue(why in r.why, "${r.why} should name $why")
        }
        broken("""{"aId":"a-1","aId":"a-2"}""", "line 1")   // duplicate key
        broken("""["a-1"]""", "line 1")
        broken("""{"aId":7}""", "aId")
        broken("""{"aId":""}""", "aId")
        broken(Char(0xFEFF) + "{\"aId\":\"a-1\"}", "BOM")
        broken("{nope", "line 1")
        broken("""{"aId":"a-1"} trailing""", "line 1")
    }

    @Test fun `a manifest with a BOM is named`() {
        lines("a.jsonl"); lines("b.jsonl")
        Files.writeString(dir.resolve("manifest.json"), Char(0xFEFF) + """{"schemaVersion":"5","runId":"r","counts":{"a":0,"b":0}}""")
        val r = read()
        assertIs<BundleRead.Broken>(r)
        assertTrue("starts with a UTF-8 BOM" in r.why, r.why)
    }

    @Test fun `bytes that are not UTF-8 are broken, not waited for, and the file is named`() {
        Files.write(dir.resolve("a.jsonl"), byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), 0x0A))
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        val r = assertIs<BundleRead.Broken>(read())
        assertTrue("a.jsonl" in r.why && "is not UTF-8" in r.why, r.why)
    }

    @Test fun `a manifest that is not UTF-8 is broken and named`() {
        lines("a.jsonl"); lines("b.jsonl")
        Files.write(dir.resolve("manifest.json"), byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()))
        val r = assertIs<BundleRead.Broken>(read())
        assertTrue("manifest.json" in r.why && "is not UTF-8" in r.why, r.why)
    }

    /** Reads normally, except that the manifest read number [nth] comes back as a different manifest. */
    private fun changingManifestAt(nth: Int): (Path) -> String {
        var manifestReads = 0
        return { p ->
            val text = Files.readString(p)
            if (p.fileName.toString() == "manifest.json" && ++manifestReads == nth) text.replace("run-1", "run-2") else text
        }
    }

    @Test fun `a bad line is not broken if the manifest changed under the reader - read again later`() {
        lines("b.jsonl")
        for (bad in listOf("""{"aId":"a-1","aId":"a-2"}""", """{"aId":7}""", Char(0xFEFF) + """{"aId":"a-1"}""", "{nope")) {
            lines("a.jsonl", bad)
            manifest(a = 1, b = 0)
            val r = assertIs<BundleRead.NotReady>(read(readText = changingManifestAt(2)), bad)
            assertTrue("changed while reading" in r.why, r.why)
        }
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-1"}""")
        manifest(a = 2, b = 0)
        assertIs<BundleRead.NotReady>(read(readText = changingManifestAt(2)))   // repeated id
    }

    @Test fun `a counted file that is gone is not broken if the manifest changed under the reader`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        manifest(a = 1, b = 1)
        assertIs<BundleRead.NotReady>(read(readText = changingManifestAt(2)))
        assertIs<BundleRead.Broken>(read())
    }

    @Test fun `a file that is not UTF-8 is not broken if the manifest changed under the reader`() {
        Files.write(dir.resolve("a.jsonl"), byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), 0x0A))
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        assertIs<BundleRead.NotReady>(read(readText = changingManifestAt(2)))
    }

    @Test fun `a manifest read as missing the second time counts as changed`() {
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        var manifestReads = 0
        val r = read(readText = { p ->
            if (p.fileName.toString() == "manifest.json" && ++manifestReads == 2) throw java.nio.file.NoSuchFileException(p.toString())
            Files.readString(p)
        })
        assertIs<BundleRead.NotReady>(r)
    }

    @Test fun `a manifest over the size cap is broken`() {
        lines("a.jsonl"); lines("b.jsonl")
        Files.writeString(dir.resolve("manifest.json"),
            """{"schemaVersion":"5","runId":"r","pad":"${"x".repeat(Bundles.MAX_MANIFEST_BYTES.toInt())}","counts":{"a":0,"b":0}}""")
        val r = assertIs<BundleRead.Broken>(read())
        assertTrue("manifest.json" in r.why && "bytes" in r.why, r.why)
    }

    @Test fun `a file whose count is zero is not read at all`() {
        Files.write(dir.resolve("a.jsonl"), byteArrayOf(0xC3.toByte(), 0x28))   // would be broken if it were read
        lines("b.jsonl", "{nope")
        manifest(a = 0, b = 0)
        val readFiles = mutableListOf<String>()
        val r = assertIs<BundleRead.Ready>(read(readText = { p -> readFiles += p.fileName.toString(); Files.readString(p) }))
        assertEquals(emptyList(), r.lines.getValue("a.jsonl"))
        assertEquals(emptyList(), r.lines.getValue("b.jsonl"))
        assertTrue(readFiles.all { it == "manifest.json" }, "$readFiles")
    }

    @Test fun `positions count non-blank lines, and the text is exact`() {
        Files.writeString(dir.resolve("a.jsonl"), "{\"aId\":\"a-1\"}\n\n  \t\r\n{ \"aId\" : \"a-2\" }\r\n")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        val r = assertIs<BundleRead.Ready>(read()).lines.getValue("a.jsonl")
        assertEquals(listOf(1, 2), r.map { it.position })
        assertEquals(listOf("a-1", "a-2"), r.map { it.id })
        assertEquals("{ \"aId\" : \"a-2\" }", r[1].text)
    }

    @Test fun `a line of other Unicode spaces is a line, and not JSON`() {
        lines("b.jsonl")
        Files.writeString(dir.resolve("a.jsonl"), " \n")
        manifest(a = 1, b = 0)
        val r = assertIs<BundleRead.Broken>(read())
        assertTrue("line 1" in r.why, r.why)
        // …and it is counted as a line, not dropped as blank:
        Files.writeString(dir.resolve("a.jsonl"), "{\"aId\":\"a-1\"}\n \n")
        manifest(a = 2, b = 0)
        assertTrue("line 2" in assertIs<BundleRead.Broken>(read()).why)
    }

    @Test fun `a line file that cannot be opened for now is not ready, not broken`() {
        lines("a.jsonl", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        val r = read(readText = { p ->
            if (p.fileName.toString() == "a.jsonl") throw java.nio.file.AccessDeniedException(p.toString())
            Files.readString(p)
        })
        assertIs<BundleRead.NotReady>(r)
    }

    @Test fun `a manifest without run id, counts or a known schema is broken`() {
        lines("a.jsonl"); lines("b.jsonl")
        manifest(a = 0, b = 0, runId = null); assertIs<BundleRead.Broken>(read())
        manifest(a = 0, b = 0, runId = " "); assertIs<BundleRead.Broken>(read())
        manifest(a = 0, b = 0, schema = "6"); assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","counts":{"a":-1,"b":0}}""")
        assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","counts":{"a":0}}""")
        assertIs<BundleRead.Broken>(read())
        Files.writeString(dir.resolve("manifest.json"), """{"schemaVersion":"5","runId":"r","runId":"s","counts":{"a":0,"b":0}}""")
        assertIs<BundleRead.Broken>(read())
    }

    @Test fun `repeated ids are broken when ids must be unique, kept when they may repeat`() {
        lines("a.jsonl", """{"aId":"a-1"}""", """{"aId":"a-1"}""")
        lines("b.jsonl")
        manifest(a = 2, b = 0)
        assertIs<BundleRead.Broken>(read(unique = true))
        assertEquals(listOf("a-1", "a-1"), assertIs<BundleRead.Ready>(read(unique = false)).lines.getValue("a.jsonl").map { it.id })
    }

    @Test fun `the manifest travels as written`() {
        lines("a.jsonl"); lines("b.jsonl")
        manifest(a = 0, b = 0)
        assertEquals(Files.readString(dir.resolve("manifest.json")), assertIs<BundleRead.Ready>(read()).manifestJson)
    }
}
