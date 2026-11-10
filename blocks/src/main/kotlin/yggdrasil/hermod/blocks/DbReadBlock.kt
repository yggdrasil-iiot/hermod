package yggdrasil.hermod.blocks

import yggdrasil.hermod.sdk.*

class DbReadBlock : Block {
    override val id = "db.read"

    override fun forward(input: BlockInput): BlockOutput {
        if (input.failAtBlockId == id) throw PermanentBlockFailure("injected failure at $id")
        val rows = mutableListOf<Record>()
        Db.connect().use { c ->
            c.createStatement().executeQuery("SELECT id, val FROM source_rows ORDER BY id").use { rs ->
                while (rs.next()) rows.add(mapOf("id" to rs.getString("id"), "val" to rs.getString("val")))
            }
        }
        return BlockOutput(rows = rows)
    }
}
