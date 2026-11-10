// registry/src/test/kotlin/hermod/registry/CanvasReadinessTest.kt
package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CanvasReadinessTest {
    private fun base(
        params: List<ParamSpec> = emptyList(),
        inputs: List<IoSpec> = emptyList(),
        outputs: List<IoSpec> = emptyList(),
        displayName: String = "디스플레이",
        description: String = "설명",
        sideEffects: List<SideEffect> = listOf(SideEffect.NONE),
        reversibility: Reversibility = Reversibility.REVERSIBLE,
    ) = BlockContract(
        id = "x.y", version = "1.0.0", category = BlockCategory.transform,
        displayName = displayName, description = description, params = params, inputs = inputs, outputs = outputs,
        forwardHandler = "k.B", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
        compensation = CompensationSpec(reversibility, CompensationKind.NONE),
        retry = RetrySpec(1, 1, 1), sideEffects = sideEffects,
    )

    @Test fun `complete contract has no diagnostics`() {
        val c = base(
            params = listOf(ParamSpec("table", "string", label = "테이블")),
            inputs = listOf(IoSpec("rows", "Record[]", label = "행")),
        )
        assertTrue(CanvasReadiness.check(c).isEmpty())
        assertTrue(CanvasReadiness.isReady(c))
    }

    @Test fun `C1 missing displayName`() {
        val codes = CanvasReadiness.check(base(displayName = "  ")).map { it.code }
        assertTrue("C1" in codes)
    }
    @Test fun `C2 missing description`() {
        assertTrue("C2" in CanvasReadiness.check(base(description = "")).map { it.code })
    }
    @Test fun `C3 param without label`() {
        val c = base(params = listOf(ParamSpec("table", "string")))   // label defaults ""
        assertTrue("C3" in CanvasReadiness.check(c).map { it.code })
    }
    @Test fun `C4 port without label`() {
        val c = base(outputs = listOf(IoSpec("out", "Record[]")))      // label ""
        assertTrue("C4" in CanvasReadiness.check(c).map { it.code })
    }
    @Test fun `C5 select widget without enumValues`() {
        val c = base(params = listOf(ParamSpec("m", "string", label = "모드", widget = "select")))
        assertTrue("C5" in CanvasReadiness.check(c).map { it.code })
    }
    @Test fun `risk red for actuation or irreversible`() {
        assertEquals("red", CanvasReadiness.risk(base(sideEffects = listOf(SideEffect.ACTUATION))))
        assertEquals("red", CanvasReadiness.risk(base(reversibility = Reversibility.IRREVERSIBLE)))
    }
    @Test fun `risk amber for external call or message send or mitigatable, green otherwise`() {
        assertEquals("amber", CanvasReadiness.risk(base(sideEffects = listOf(SideEffect.EXTERNAL_CALL))))
        assertEquals("amber", CanvasReadiness.risk(base(sideEffects = listOf(SideEffect.MESSAGE_SEND))))
        assertEquals("amber", CanvasReadiness.risk(base(reversibility = Reversibility.MITIGATABLE)))
        assertEquals("green", CanvasReadiness.risk(base()))
    }
}
