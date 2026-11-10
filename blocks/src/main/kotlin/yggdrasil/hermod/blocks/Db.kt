package yggdrasil.hermod.blocks

import java.sql.Connection
import java.sql.DriverManager

/** Test code can mutate these to point at a Testcontainers instance. */
object DbConnectionOverride {
    var url: String? = null
    var user: String? = null
    var pass: String? = null
}

object Db {
    val url: String get() = DbConnectionOverride.url ?: System.getenv("HERMOD_DB_URL") ?: "jdbc:postgresql://localhost:15432/hermod"
    val user: String get() = DbConnectionOverride.user ?: System.getenv("HERMOD_DB_USER") ?: "hermod"
    val pass: String get() = DbConnectionOverride.pass ?: System.getenv("HERMOD_DB_PASS") ?: "hermod"

    fun connect(): Connection = DriverManager.getConnection(url, user, pass)
}
