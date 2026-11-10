package yggdrasil.hermod.delegation

import java.sql.Connection
import java.sql.DriverManager

object DelegationDbConnectionOverride { var url: String? = null; var user: String? = null; var pass: String? = null }

/** Minimal JDBC factory for delegation audit; reads the same HERMOD_DB_* env as :opcua/:blocks. */
object DelegationDb {
    val url: String get() = DelegationDbConnectionOverride.url ?: System.getenv("HERMOD_DB_URL") ?: "jdbc:postgresql://localhost:15432/hermod"
    val user: String get() = DelegationDbConnectionOverride.user ?: System.getenv("HERMOD_DB_USER") ?: "hermod"
    val pass: String get() = DelegationDbConnectionOverride.pass ?: System.getenv("HERMOD_DB_PASS") ?: "hermod"
    fun connect(): Connection = DriverManager.getConnection(url, user, pass)
}
