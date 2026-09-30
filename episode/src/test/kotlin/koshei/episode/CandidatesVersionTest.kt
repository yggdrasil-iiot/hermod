package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CandidatesVersionTest {
    private val two = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-1")), emptyList())

    @Test fun `format is sha256 then 64 lowercase hex`() {
        assertTrue(Regex("sha256:[0-9a-f]{64}").matches(candidatesVersion(two)))
    }

    @Test fun `it hashes the canonical projectionVersion plus id-sorted candidates`() {
        val canonical = """{"candidates":[{"candidateId":"ESCALATE","kind":"ESCALATE","ref":null}],"projectionVersion":"1"}"""
        assertEquals("sha256:" + Jcs.sha256Hex(canonical), candidatesVersion(listOf(Candidate.ESCALATE)))
    }

    @Test fun `input order does not matter`() {
        assertEquals(candidatesVersion(two), candidatesVersion(two.reversed()))
    }

    @Test fun `a new projection version changes the version`() {
        assertNotEquals(candidatesVersion(two), candidatesVersion(two, projectionVersion = "2"))
    }

    @Test fun `different candidate sets differ`() {
        val other = projectCandidates(PicassoRun1.snapshot(searchIds = listOf("search-4")), emptyList())
        assertNotEquals(candidatesVersion(two), candidatesVersion(other))
    }
}
