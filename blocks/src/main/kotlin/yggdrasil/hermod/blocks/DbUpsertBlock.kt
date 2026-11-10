package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*

class DbUpsertBlock : Block {
    override val id = "db.upsert"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        if (input.slowMs > 0) Thread.sleep(input.slowMs) // widen in-flight window for the mid-activity kill (crash script)
        val ids = input.rows.mapNotNull { it["id"] }
        val prior = mutableListOf<Record>()
        val preExisting = HashSet<String>()
        Db.connect().use { c ->
            // pre-read existing PKs + snapshots (single-writer assumption; see §5.1/§12)
            if (ids.isNotEmpty()) {
                val placeholders = ids.joinToString(",") { "?" }
                c.prepareStatement("SELECT id, val FROM target_rows WHERE id IN ($placeholders)").use { ps ->
                    ids.forEachIndexed { i, v -> ps.setString(i + 1, v) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            preExisting.add(rs.getString(1))
                            prior.add(mapOf("id" to rs.getString(1), "val" to rs.getString(2)))
                        }
                    }
                }
            }
            for (row in input.rows) {
                c.prepareStatement("INSERT INTO target_rows(id,val) VALUES(?,?) ON CONFLICT(id) DO UPDATE SET val=EXCLUDED.val").use { ps ->
                    ps.setString(1, row["id"]); ps.setString(2, row["val"]); ps.executeUpdate()
                }
            }
        }
        val insertedIds = ids.filter { it !in preExisting }
        return BlockOutput(rows = input.rows, boundState = mapOf(
            "priorValues" to Json.write(prior),
            "insertedIds" to Json.write(insertedIds),
        ))
    }

    override fun compensate(boundState: Map<String, String>, ctx: CompensationContext): CompensationAction {
        val prior: List<Record> = Json.read(boundState["priorValues"] ?: "[]")
        val inserted: List<String> = Json.read(boundState["insertedIds"] ?: "[]")
        Db.connect().use { c ->
            for (row in prior) {  // restore updates
                c.prepareStatement("UPDATE target_rows SET val=? WHERE id=?").use { ps ->
                    ps.setString(1, row["val"]); ps.setString(2, row["id"]); ps.executeUpdate()
                }
            }
            for (id in inserted) {  // delete inserts
                c.prepareStatement("DELETE FROM target_rows WHERE id=?").use { ps ->
                    ps.setString(1, id); ps.executeUpdate()
                }
            }
        }
        return CompensationAction("RESTORE", "restored ${prior.size} update(s), deleted ${inserted.size} insert(s)")
    }
}
