package koshei.episode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PicassoAnswersTest {
    @Test fun `an APPROVED answer carries the execution and what went out`() {
        val j = judgePicassoAnswer(PicassoAnswers.approved())
        assertIs<DispatchJudgement.Accepted>(j)
        assertEquals("exec-42", j.executionId)
        assertEquals("pick_place", j.delivered[0].get("skillType").textValue())
    }

    @Test fun `every refusal of schema 2 is understood - two may have landed, the rest are refusals`() {
        assertEquals(15, PICASSO_REFUSALS_V2.size)
        for (r in PICASSO_REFUSALS_V2) {
            val j = judgePicassoAnswer(PicassoAnswers.refused(r))
            if (r == "NO_PROPOSAL" || r == "REMEDY_NOT_APPLIED") assertEquals(DispatchJudgement.MayHaveLanded(r), j, r)
            else assertEquals(DispatchJudgement.Refused(r), j, r)
        }
    }

    @Test fun `anything not understood is an unknown answer`() {
        listOf(
            PicassoAnswers.refused("SOMETHING_NEW"),
            PicassoAnswers.refused("REFUSED_BY_GATE", schema = "3"),
            """{"outcome":"APPROVED","executionId":"e","steps":[]}""",
            """{"schemaVersion":"2","outcome":"APPROVED","steps":[]}""",
            """{"schemaVersion":"2","outcome":"APPROVED","executionId":"e"}""",
            """{"schemaVersion":"2","outcome":"MAYBE"}""",
            """{"schemaVersion":"2","outcome":"REFUSED","refusal":"NO_PROPOSAL","refusal":"REFUSED_BY_GATE"}""",
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
