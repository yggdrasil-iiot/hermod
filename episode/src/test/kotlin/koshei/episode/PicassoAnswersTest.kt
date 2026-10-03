package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PicassoAnswersTest {
    @Test fun `an APPROVED answer carries the instance, the execution, every step's unit and what went out`() {
        val j = judgePicassoAnswer(PicassoAnswers.approved(units = listOf("remedy-1-pick_place", "remedy-2-pick_place")))
        assertIs<DispatchJudgement.Accepted>(j)
        assertEquals("mw-1", j.instanceId)
        assertEquals("exec-42", j.executionId)
        assertEquals(listOf("remedy-1-pick_place", "remedy-2-pick_place"), j.units)
        assertEquals("pick_place", j.delivered[0].get("skillType").textValue())
    }

    @Test fun `every refusal of schema 4 is understood - three may have landed, the rest are refusals`() {
        assertEquals(16, PICASSO_REFUSALS.size)
        for (r in PICASSO_REFUSALS) {
            val j = judgePicassoAnswer(PicassoAnswers.refused(r))
            if (r == "NO_PROPOSAL" || r == "REMEDY_NOT_APPLIED" || r == "CONSUMED") assertEquals(DispatchJudgement.MayHaveLanded(r), j, r)
            else assertEquals(DispatchJudgement.Refused(r), j, r)
        }
    }

    @Test fun `CONSUMED keeps the execution its consumption record names`() {
        val j = judgePicassoAnswer(PicassoAnswers.refused("CONSUMED", consumed = PicassoAnswers.consumedRecord("exec-7")))
        assertEquals(DispatchJudgement.MayHaveLanded("CONSUMED", consumedExecutionId = "exec-7"), j)
    }

    @Test fun `anything not understood is an unknown answer`() {
        val step = """{"unitId":"remedy-1-pick_place","skillType":"pick_place","parameters":{}}"""
        listOf(
            PicassoAnswers.refused("SOMETHING_NEW"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "2"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"),
            // schema 2's APPROVED, as koshei read it before: no instanceId, no unitId
            """{"schemaVersion":"2","contractSemver":"1.0.0","outcome":"APPROVED","executionId":"e","steps":[{"skillType":"pick_place","parameters":{}}]}""",
            """{"schemaVersion":"4","outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":" ","outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":7,"outcome":"APPROVED","executionId":"e","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","steps":[$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e"}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[{"skillType":"pick_place","parameters":{}}]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[{"unitId":"","skillType":"pick_place"}]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":[$step,$step]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"APPROVED","executionId":"e","steps":["remedy-1-pick_place"]}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"MAYBE"}""",
            """{"schemaVersion":"4","instanceId":"mw","outcome":"REFUSED","refusal":"NO_PROPOSAL","refusal":"REFUSED_BY_GATE"}""",
            "{",
            "[]",
        ).forEach { assertIs<DispatchJudgement.AnswerUnknown>(judgePicassoAnswer(it), it) }
    }

    @Test fun `an answer of the wrong kind for the candidate is unknown`() {
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.OPERATOR_DECISION, DispatchResult.Answer(PicassoAnswers.approved())))
        assertIs<DispatchJudgement.AnswerUnknown>(judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.PersonTask, judgeDispatch(CandidateKind.CHOOSE_SOURCE, DispatchResult.PersonTaskIssued))
        assertEquals(DispatchJudgement.Uncertain("timeout"), judgeDispatch(CandidateKind.APPROVE_REMEDY, DispatchResult.Uncertain("timeout")))
    }
}
