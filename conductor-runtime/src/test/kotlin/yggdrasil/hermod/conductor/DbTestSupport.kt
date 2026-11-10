package yggdrasil.hermod.conductor

import yggdrasil.hermod.blocks.DbConnectionOverride
import java.sql.DriverManager

/**
 * Conductor-runtime local copy of the blocks DbTestSupport.
 * Routes Db.connect() to a Testcontainers Postgres instance and offers SQL helpers.
 */
object DbTestSupport {
    private var url = ""; private var user = ""; private var pass = ""

    fun override(jdbcUrl: String, u: String, p: String) {
        url = jdbcUrl; user = u; pass = p
        DbConnectionOverride.url = jdbcUrl
        DbConnectionOverride.user = u
        DbConnectionOverride.pass = p
    }

    fun exec(sql: String) = DriverManager.getConnection(url, user, pass).use { it.createStatement().execute(sql) }

    fun count(table: String, where: String = "TRUE"): Int =
        DriverManager.getConnection(url, user, pass).use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM $table WHERE $where").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
}
