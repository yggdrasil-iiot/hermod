package yggdrasil.hermod.authoring

import yggdrasil.hermod.core.BlockContract
import yggdrasil.hermod.core.SemVer
import yggdrasil.hermod.registry.CanvasReadiness
import yggdrasil.hermod.registry.Registry
import org.springframework.stereotype.Service

@Service
class PaletteService(private val registry: Registry) {
    private fun card(c: BlockContract, versions: List<String>): PaletteCard = PaletteCard(
        id = c.id, latestVersion = c.version, versions = versions,
        category = c.category.name, displayName = c.displayName, description = c.description,
        risk = CanvasReadiness.risk(c),
        inputs = c.inputs.map { PortCard(it.name, it.type, it.label) },
        outputs = c.outputs.map { PortCard(it.name, it.type, it.label) },
        params = c.params.map { ParamCard(it.name, it.type, it.required, it.label, it.help, it.default, it.widget, it.enumValues) },
        complete = CanvasReadiness.isReady(c),
    )

    /** Palette = canvas-ready, non-deprecated, SemVer-latest per id. Deprecation filtering lives HERE
     *  only (Registry resolution stays untouched — spec §5.3). SemVer (not String.max) per resolveSpec idiom. */
    fun palette(): List<PaletteCard> =
        registry.listWithFlags()
            .groupBy { it.first.id }
            .mapNotNull { (_, entries) ->
                val ready = entries.filter { (c, dep) -> !dep && CanvasReadiness.isReady(c) }
                if (ready.isEmpty()) return@mapNotNull null
                val best = ready.mapNotNull { SemVer.parseOrNull(it.first.version) }.maxOrNull()
                val latest = if (best != null) ready.first { it.first.version == best.toString() }.first
                             else ready.first().first
                // versions list shows ALL known versions (incl. deprecated/incomplete) for the picker;
                // latestVersion (inside card(latest, …)) points at the best canvas-ready non-deprecated one.
                val versionsDesc = entries.mapNotNull { SemVer.parseOrNull(it.first.version) }.sortedDescending().map { it.toString() }
                card(latest, versionsDesc.ifEmpty { entries.map { it.first.version } })
            }

    /** Every block incl. incomplete/deprecated, with readiness diagnostics — engineer browse view. */
    fun allBlocks(): List<BlockRow> =
        registry.listWithFlags().map { (c, dep) ->
            BlockRow(
                card = card(c, listOf(c.version)),
                deprecated = dep,
                diagnostics = CanvasReadiness.check(c).map { mapOf("code" to it.code, "message" to it.message) },
            )
        }
}
