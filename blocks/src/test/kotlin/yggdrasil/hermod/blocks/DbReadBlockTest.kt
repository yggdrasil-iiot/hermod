package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*
import kotlin.test.*

class DbReadBlockTest {
    @BeforeTest fun setup() = DbTestSupport.startAndReset()
    @AfterTest fun teardown() = DbTestSupport.stop()

    @Test fun `reads all source rows ordered by id`() {
        DbTestSupport.exec("INSERT INTO source_rows(id,val) VALUES('A1','x'),('A2','y')")
        val out = DbReadBlock().forward(BlockInput())
        assertEquals(2, out.rows.size)
        assertEquals(listOf("A1", "A2"), out.rows.mapNotNull { it["id"] })
        assertEquals("x", out.rows.first { it["id"] == "A1" }["val"])
    }

    @Test fun `forward honors failAtBlockId injection`() {
        assertFailsWith<PermanentBlockFailure> {
            DbReadBlock().forward(BlockInput(failAtBlockId = "db.read"))
        }
    }
}
