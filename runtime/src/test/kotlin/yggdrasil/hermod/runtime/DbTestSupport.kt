package yggdrasil.hermod.runtime

import yggdrasil.hermod.blocks.DbConnectionOverride
import java.sql.DriverManager

/**
 * Runtime-local copy of the blocks DbTestSupport (blocks' TEST classes are not on runtime's test
 * classpath; blocks' MAIN classes — Db/DbConnectionOverride/handlers — are, via the impl dep).
 * Routes Db.connect() at a Testcontainers instance and offers tiny SQL helpers.
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

    fun count(table: String): Int =
        DriverManager.getConnection(url, user, pass).use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM $table").use { rs -> rs.next(); rs.getInt(1) }
        }

    fun value(table: String, id: String): String? =
        DriverManager.getConnection(url, user, pass).use { c ->
            c.prepareStatement("SELECT val FROM $table WHERE id=?").use { ps ->
                ps.setString(1, id); ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    /** convenience for the §9.1 tests: value in target_rows by id. */
    fun queryVal(id: String): String? = value("target_rows", id)
}
