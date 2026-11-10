package yggdrasil.hermod.opcua
import kotlin.test.*
class ApplyPortFactoryTest {
    @Test fun `unset defaults to NCMD`() = assertEquals(ApplyPortFactory.Mode.NCMD, ApplyPortFactory.mode(null))
    @Test fun `blank defaults to NCMD`() = assertEquals(ApplyPortFactory.Mode.NCMD, ApplyPortFactory.mode("  "))
    @Test fun `ncmd is NCMD (case-insensitive)`() = assertEquals(ApplyPortFactory.Mode.NCMD, ApplyPortFactory.mode("NCMD"))
    @Test fun `direct must be named explicitly`() = assertEquals(ApplyPortFactory.Mode.DIRECT, ApplyPortFactory.mode(" Direct "))

    @Test fun `unknown value is rejected, never falls back to DIRECT`() {
        val e = assertFailsWith<IllegalArgumentException> { ApplyPortFactory.mode("bogus") }
        assertContains(e.message!!, "HERMOD_APPLY_MODE")
        assertContains(e.message!!, "bogus")
    }

    @Test fun `direct banner warns that Heimdall is bypassed`() {
        val line = ApplyPortFactory.banner("worker-1", ApplyPortFactory.Mode.DIRECT)
        assertContains(line, "WARNING")
        assertContains(line, "bypass")
        assertContains(line, "Heimdall")
    }

    @Test fun `ncmd banner carries no warning`() {
        val line = ApplyPortFactory.banner("worker-1", ApplyPortFactory.Mode.NCMD)
        assertContains(line, "ncmd")
        assertFalse("WARNING" in line)
    }
}
