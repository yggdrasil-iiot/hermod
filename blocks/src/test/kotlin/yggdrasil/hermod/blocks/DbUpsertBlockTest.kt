package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*
import kotlin.test.*

class DbUpsertBlockTest {
    @BeforeTest fun setup() = DbTestSupport.startAndReset()  // truncates target_rows
    @AfterTest fun teardown() = DbTestSupport.stop()

    @Test fun `forward upserts and records insertedIds vs priorValues`() {
        DbTestSupport.exec("INSERT INTO target_rows(id,val) VALUES('A','old')")  // A pre-exists -> update
        val out = DbUpsertBlock().forward(BlockInput(rows = listOf(
            mapOf("id" to "A", "val" to "new"),   // update
            mapOf("id" to "B", "val" to "fresh"), // insert
        )))
        assertEquals("new", DbTestSupport.queryVal("A"))
        assertEquals("fresh", DbTestSupport.queryVal("B"))
        val inserted = Json.read<List<String>>(out.boundState["insertedIds"]!!)
        assertEquals(listOf("B"), inserted, "only B was newly inserted")
    }

    @Test fun `compensate restores updates AND deletes inserts (complete STATIC)`() {
        DbTestSupport.exec("INSERT INTO target_rows(id,val) VALUES('A','old')")
        val out = DbUpsertBlock().forward(BlockInput(rows = listOf(
            mapOf("id" to "A", "val" to "new"),
            mapOf("id" to "B", "val" to "fresh"),
        )))
        DbUpsertBlock().compensate(out.boundState, CompensationContext())
        assertEquals("old", DbTestSupport.queryVal("A"), "A restored to prior value")
        assertNull(DbTestSupport.queryVal("B"), "B (inserted) deleted by compensation")
    }
}
