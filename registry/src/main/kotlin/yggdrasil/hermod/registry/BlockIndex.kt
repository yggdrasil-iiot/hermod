package yggdrasil.hermod.registry

import java.sql.Connection

/** Postgres index of published plugin blocks. `connect` is injected so tests can point at a container. */
class BlockIndex(private val connect: () -> Connection) {
    data class Row(val id: String, val version: String, val manifestJson: String, val jarPath: String, val sha256: String, val deprecated: Boolean = false)

    fun ensureSchema() {
        val sql = javaClass.getResourceAsStream("/registry-schema.sql")!!.bufferedReader().readText()
        connect().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    fun insert(r: Row) = connect().use { c ->
        c.prepareStatement("INSERT INTO block_index(id,version,manifest_json,jar_path,sha256) VALUES(?,?,?,?,?)").use { ps ->
            ps.setString(1, r.id); ps.setString(2, r.version); ps.setString(3, r.manifestJson)
            ps.setString(4, r.jarPath); ps.setString(5, r.sha256); ps.executeUpdate()   // dup PK -> SQLException
        }
    }

    fun find(id: String, version: String): Row? = connect().use { c ->
        c.prepareStatement("SELECT manifest_json,jar_path,sha256,deprecated FROM block_index WHERE id=? AND version=?").use { ps ->
            ps.setString(1, id); ps.setString(2, version)
            ps.executeQuery().use { rs -> if (rs.next()) Row(id, version, rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)) else null }
        }
    }

    fun list(): List<Row> = connect().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT id,version,manifest_json,jar_path,sha256,deprecated FROM block_index").use { rs ->
                buildList { while (rs.next()) add(Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getBoolean(6))) }
            }
        }
    }

    fun deprecate(id: String, version: String) = connect().use { c ->
        c.prepareStatement("UPDATE block_index SET deprecated=true WHERE id=? AND version=?").use { ps ->
            ps.setString(1, id); ps.setString(2, version); ps.executeUpdate()
        }
    }

    fun versionsOf(id: String): List<String> = connect().use { c ->
        c.prepareStatement("SELECT version FROM block_index WHERE id=?").use { ps ->
            ps.setString(1, id)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }
}
