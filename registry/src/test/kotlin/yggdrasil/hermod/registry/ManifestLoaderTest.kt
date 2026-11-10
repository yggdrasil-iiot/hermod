package yggdrasil.hermod.registry

import yggdrasil.hermod.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ManifestLoaderTest {
    private fun yaml(name: String) = javaClass.getResourceAsStream("/$name")!!.bufferedReader().readText()

    @Test fun `loads a valid manifest into a BlockContract`() {
        val c = ManifestLoader.load(yaml("valid-manifest.yaml"))
        assertEquals("db.upsert", c.id)
        assertEquals("1.2.0", c.version)
        assertEquals(BlockCategory.sink, c.category)
        assertEquals(IdempotencyStrategy.UPSERT, c.idempotency.strategy)
        assertEquals("row:id", c.idempotency.keyExpression)
        assertEquals(Reversibility.REVERSIBLE, c.compensation.reversibility)
        assertEquals(listOf("priorValues", "insertedIds"), c.compensation.requiresState)
        assertEquals(listOf(SideEffect.DB_WRITE), c.sideEffects)
        assertEquals(5, c.retry.maxAttempts)
        assertEquals("yggdrasil.hermod.blocks.DbUpsertBlock", c.forwardHandler)
        assertEquals("yggdrasil.hermod.blocks.DbUpsertBlock#compensate", c.compensation.handler)
        assertEquals(200L, c.retry.initialMs)
        assertEquals(10_000L, c.retry.maxMs)
        assertEquals(30_000L, c.timeoutMs)
        assertFalse(c.human.requireApprovalBefore)
        assertEquals(listOf("priorValues", "insertedIds"), c.stateBinding.map { it.key })
    }

    @Test fun `fromJson round-trips the same contract as fromYaml`() {
        val text = yaml("valid-manifest.yaml")
        val viaYaml = ManifestLoader.fromYaml(text)
        val json = ManifestLoader.toJson(viaYaml)            // ManifestDto-shape JSON
        val viaJson = ManifestLoader.fromJson(json)
        assertEquals(viaYaml, viaJson)
    }

    @Test fun `existing builtin manifest parses with empty presentation fields by default`() {
        val c = ManifestLoader.load(yaml("valid-manifest.yaml"))   // db.upsert shape, no presentation keys
        // additive fields default to empty/null — parsing unchanged
        assertEquals("", c.params.first().label)
        assertEquals(emptyList(), c.params.first().enumValues)
        assertEquals("", c.inputs.first().label)
    }

    @Test fun `presentation fields survive a YAML to contract to JSON to contract round-trip`() {
        val yml = """
            id: demo.present
            version: 1.0.0
            category: transform
            displayName: "데모"
            description: "표현 메타 라운드트립"
            params:
              - { name: mode, type: string, required: true, label: "모드", help: "동작 모드", default: "fast", widget: select, enumValues: ["fast", "safe"] }
            inputs:
              - { name: rows, type: "Record[]", label: "입력 행" }
            outputs:
              - { name: out, type: "Record[]", label: "출력 행" }
            forward: { handler: "yggdrasil.hermod.demo.PresentBlock" }
            idempotency: { strategy: NONE }
            compensation: { reversibility: REVERSIBLE, kind: NONE }
            retry: { maxAttempts: 1, backoff: { initialMs: 1, maxMs: 1 } }
            sideEffects: [NONE]
            human: { requireApprovalBefore: false }
        """.trimIndent()
        val fromYaml = ManifestLoader.fromYaml(yml)
        val roundTripped = ManifestLoader.fromJson(ManifestLoader.toJson(fromYaml))
        assertEquals(fromYaml, roundTripped)                         // full structural equality
        assertEquals("모드", roundTripped.params.first().label)      // and the fields are actually present
        assertEquals("select", roundTripped.params.first().widget)
        assertEquals(listOf("fast", "safe"), roundTripped.params.first().enumValues)
        assertEquals("입력 행", roundTripped.inputs.first().label)
        assertEquals("출력 행", roundTripped.outputs.first().label)
    }
}
