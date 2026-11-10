package yggdrasil.hermod.opcua

import kotlin.test.*

class TemplatesParseTest {
    @Test fun `packml exemplar parses as a SiteModel`() {
        // Using the generated classpath copy (processResources copies model/ incl. templates/ at build time).
        // This verifies both parse-well-formedness and that processResources delivers the templates/ subtree.
        val stream = TemplatesParseTest::class.java.getResourceAsStream("/model/templates/packml-unit.yaml")
            ?: error("packml-unit.yaml not on classpath — run :opcua:processResources first")
        val m = SiteModel.parse(stream.bufferedReader().use { it.readText() })
        assertEquals(
            setOf("packml.stateCurrent", "packml.cntrlCmd", "packml.unitMode", "packml.machSpeedCmd"),
            m.setpoints().keys
        )
        // NOTE: intentionally NOT calling ModelValidator.validate(m) — Int tags are non-runnable by design (R1 Double-only).
        // The Int setpoints would fail the runnable validator; that is the point of this being a template.
    }
}
