package yggdrasil.hermod.authoring

import yggdrasil.hermod.core.*
import yggdrasil.hermod.registry.Registry
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class PaletteServiceTest {
    private fun c(id: String, version: String, ready: Boolean) = BlockContract(
        id = id, version = version, category = BlockCategory.transform,
        displayName = if (ready) "이름" else "", description = if (ready) "설명" else "",
        params = if (ready) listOf(ParamSpec("p", "string", label = "라벨")) else listOf(ParamSpec("p", "string")),
        forwardHandler = "k.B", idempotency = IdempotencySpec(IdempotencyStrategy.NONE),
        compensation = CompensationSpec(Reversibility.REVERSIBLE, CompensationKind.NONE), retry = RetrySpec(1, 1, 1),
    )

    @Test fun `palette excludes incomplete and deprecated, picks SemVer-latest non-deprecated`() {
        val reg = mock(Registry::class.java)
        given(reg.listWithFlags()).willReturn(listOf(
            c("ready", "1.9.0", true) to false,
            c("ready", "1.10.0", true) to false,     // SemVer-greater than 1.9.0 (string-greater would pick 1.9.0)
            c("incomplete", "1.0.0", false) to false,
            c("gone", "1.0.0", true) to true,         // deprecated → excluded
        ))
        val svc = PaletteService(reg)
        val palette = svc.palette()
        assertEquals(setOf("ready"), palette.map { it.id }.toSet())
        assertEquals("1.10.0", palette.first { it.id == "ready" }.latestVersion)
    }

    @Test fun `palette hides a fully-deprecated id but allBlocks still shows it`() {
        val reg = mock(Registry::class.java)
        given(reg.listWithFlags()).willReturn(listOf(c("gone", "1.0.0", true) to true))
        val svc = PaletteService(reg)
        assertNull(svc.palette().firstOrNull { it.id == "gone" })
        assertNotNull(svc.allBlocks().firstOrNull { it.card.id == "gone" })
        assertEquals(true, svc.allBlocks().first { it.card.id == "gone" }.deprecated)
    }
}
