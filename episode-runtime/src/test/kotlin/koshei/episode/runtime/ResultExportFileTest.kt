package koshei.episode.runtime

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResultExportFileTest {
    @TempDir lateinit var dir: Path

    /** A line exactly as picasso `ResultExport.line` writes it (main 3d6530a): every field, in its order. */
    private fun picassoLine(id: String = "resp-1", instance: String = "mw-1", connection: String = "CONNECTION_STATE_ONLINE") =
        """{"schemaVersion":"1","contractSemver":"0.9.0","instanceId":"$instance","jobResponseId":"$id","jobOrderId":"PATROL-1",""" +
            """"executionId":"exec-1","version":1,"physicalState":"PHYSICALLY_DONE","requiredEvidence":"E1","reachedEvidence":"E1",""" +
            """"completedUnits":["remedy-1-pick_place"],"unverifiedUnits":[],"inDoubtUnits":[],"incompleteUnits":{},""" +
            """"operatorRequired":false,"residualHold":{"kind":"HOLD_KIND_EMPTY","objectRef":"","reason":""},"autoResolvesInDoubt":true,"results":{},"blockedBy":[],"connection":"$connection"}"""

    private fun write(text: String) = Files.writeString(dir.resolve(ResultExportFile.FILE), text)

    private fun ready(): ResultExportFile.Read.Ready = assertIs<ResultExportFile.Read.Ready>(ResultExportFile.read(dir))

    @Test fun `no file is not ready yet`() {
        assertIs<ResultExportFile.Read.NotReady>(ResultExportFile.read(dir))
    }

    @Test fun `an empty file has no lines and no run`() {
        write("")
        val r = ready()
        assertEquals(emptyList(), r.lines)
        assertNull(r.runId)
        assertNull(r.broken)
    }

    @Test fun `picasso's lines are read in order - the id pairs the instance with the report, repeats are kept`() {
        write(picassoLine("resp-1") + "\n" + picassoLine("resp-2") + "\n" + picassoLine("resp-1") + "\n")
        val r = ready()
        assertEquals(listOf("mw-1/resp-1", "mw-1/resp-2", "mw-1/resp-1"), r.lines.map { it.id })
        assertEquals(listOf(1, 2, 3), r.lines.map { it.position })
        assertEquals("mw-1", r.runId)
        assertNull(r.broken)
    }

    @Test fun `a last line without its newline is not a line yet`() {
        write(picassoLine("resp-1") + "\n" + picassoLine("resp-2").take(40))
        assertEquals(listOf("mw-1/resp-1"), ready().lines.map { it.id })
        write(picassoLine("resp-1") + "\n" + picassoLine("resp-2") + "\n")
        assertEquals(listOf("mw-1/resp-1", "mw-1/resp-2"), ready().lines.map { it.id })
    }

    @Test fun `CRLF endings and blank lines are read like picasso's own`() {
        write(picassoLine("resp-1") + "\r\n\r\n  \n" + picassoLine("resp-2") + "\r\n")
        assertEquals(listOf(1, 2), ready().lines.map { it.position })
    }

    @Test fun `lines of two instances in one file are both read - the run is the first line's instance`() {
        write(picassoLine("resp-1", "mw-1") + "\n" + picassoLine("resp-1", "mw-2") + "\n")
        val r = ready()
        assertEquals(listOf("mw-1/resp-1", "mw-2/resp-1"), r.lines.map { it.id })
        assertEquals("mw-1", r.runId)
    }

    @Test fun `a line it cannot read stops the file there - the lines before it are read`() {
        val bad = listOf(
            "{not json" to "strict JSON",
            "[1]" to "strict JSON",
            """{"schemaVersion":"1","instanceId":"mw-1","jobResponseId":"r","jobResponseId":"s"}""" to "strict JSON",
            Char(0xFEFF) + picassoLine("resp-2") to "BOM",
            picassoLine("resp-2").replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":\"2\"") to "schemaVersion",
            picassoLine("resp-2").replace("\"schemaVersion\":\"1\"", "\"schemaVersion\":1") to "schemaVersion",
            picassoLine("resp-2").replace("\"instanceId\":\"mw-1\"", "\"instanceId\":\" \"") to "instanceId",
            picassoLine("resp-2").replace("\"jobResponseId\":\"resp-2\"", "\"jobResponseId\":null") to "jobResponseId",
        )
        for ((line, why) in bad) {
            write(picassoLine("resp-1") + "\n" + line + "\n" + picassoLine("resp-3") + "\n")
            val r = ready()
            assertEquals(listOf("mw-1/resp-1"), r.lines.map { it.id }, why)
            assertTrue(r.broken!!.contains(why) && r.broken!!.contains("line 2"), "$why: ${r.broken}")
        }
    }

    @Test fun `a broken first line leaves no run`() {
        write("{not json\n")
        val r = ready()
        assertNull(r.runId)
        assertTrue(r.broken!!.contains("line 1"), r.broken)
    }

    @Test fun `a file that is not UTF-8 is broken`() {
        write("x\n")
        val notUtf8 = byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte(), '\n'.code.toByte())
        val r = assertIs<ResultExportFile.Read.Broken>(ResultExportFile.read(dir) { notUtf8 })
        assertTrue("not UTF-8" in r.why, r.why)
    }

    @Test fun `a last piece torn partway through a character is not a line yet - not broken`() {
        val torn = "{\"reason\":\"한".toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) }   // 한 is 3 bytes: keep 2
        Files.write(dir.resolve(ResultExportFile.FILE), (picassoLine("resp-1") + "\n").toByteArray(Charsets.UTF_8) + torn)
        val r = ready()
        assertEquals(listOf("mw-1/resp-1"), r.lines.map { it.id })
        assertNull(r.broken)
    }

    @Test fun `a complete line with a byte that is not UTF-8 is broken`() {
        val bad = picassoLine("resp-2").toByteArray(Charsets.UTF_8).let { it.copyOf(it.size - 1) + byteArrayOf(0xFF.toByte(), '}'.code.toByte()) }
        Files.write(dir.resolve(ResultExportFile.FILE), (picassoLine("resp-1") + "\n").toByteArray(Charsets.UTF_8) + bad + "\n".toByteArray())
        val r = assertIs<ResultExportFile.Read.Broken>(ResultExportFile.read(dir))
        assertTrue("not UTF-8" in r.why, r.why)
    }
    @Test fun `a line becomes a signal with the instance and the connection`() {
        write(picassoLine("resp-1", connection = "CONNECTION_STATE_UNSPECIFIED") + "\n")
        val line = assertIs<ResultExportFile.Line.Routed>(ResultExportFile.of(ready().lines.single().node, 5))
        assertEquals("PATROL-1", line.jobOrderId)
        val s = line.signal
        assertEquals(listOf("resp-1", "exec-1", "mw-1", "CONNECTION_STATE_UNSPECIFIED"), listOf(s.jobResponseId, s.executionId, s.picassoInstanceId, s.connection))
        assertEquals(listOf("remedy-1-pick_place"), s.completedUnits)
        assertEquals(listOf(emptyList<String>(), emptyList()), listOf(s.inDoubtUnits, s.unverifiedUnits))
        assertEquals(false, s.operatorRequired)
        assertEquals(5L, s.receivedAtMillis)
    }

    @Test fun `null or absent is not reported, a wrong shape is broken, no order is unrouted`() {
        fun of(json: String) = ResultExportFile.of(strictJson.readTree(json), 0)
        val base = """"schemaVersion":"1","instanceId":"mw-1","jobResponseId":"r""""
        val nulls = assertIs<ResultExportFile.Line.Routed>(of("""{$base,"jobOrderId":"O","inDoubtUnits":null,"connection":null}""")).signal
        assertNull(nulls.inDoubtUnits)
        assertNull(nulls.connection)
        assertNull(nulls.completedUnits)
        assertIs<ResultExportFile.Line.Broken>(of("""{$base,"jobOrderId":"O","inDoubtUnits":"u"}"""))
        assertIs<ResultExportFile.Line.Broken>(of("""{$base,"jobOrderId":"O","connection":1}"""))
        assertIs<ResultExportFile.Line.Broken>(of("""{$base,"jobOrderId":"O","operatorRequired":"no"}"""))
        assertEquals(ResultExportFile.Line.Unrouted("r"), of("""{$base}"""))
        assertEquals(ResultExportFile.Line.Unrouted("r"), of("""{$base,"jobOrderId":" "}"""))
    }
}
