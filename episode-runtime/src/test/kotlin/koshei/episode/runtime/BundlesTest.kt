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
        assertIs<BundleRead.Broken>(read())
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

    @Test fun `bytes that are not UTF-8 are broken, not waited for`() {
        Files.write(dir.resolve("a.jsonl"), byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), 0x0A))
        lines("b.jsonl")
        manifest(a = 1, b = 0)
        assertIs<BundleRead.Broken>(read())
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
