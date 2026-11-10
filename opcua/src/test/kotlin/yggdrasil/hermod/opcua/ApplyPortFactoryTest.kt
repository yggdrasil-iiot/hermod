package yggdrasil.hermod.opcua
import kotlin.test.*
class ApplyPortFactoryTest {
    @Test fun `unset defaults to DIRECT`() = assertEquals(ApplyPortFactory.Mode.DIRECT, ApplyPortFactory.mode(null))
    @Test fun `direct is DIRECT`() = assertEquals(ApplyPortFactory.Mode.DIRECT, ApplyPortFactory.mode("direct"))
    @Test fun `ncmd is NCMD (case-insensitive)`() = assertEquals(ApplyPortFactory.Mode.NCMD, ApplyPortFactory.mode("NCMD"))
    @Test fun `unknown value falls back to DIRECT`() = assertEquals(ApplyPortFactory.Mode.DIRECT, ApplyPortFactory.mode("bogus"))
}
