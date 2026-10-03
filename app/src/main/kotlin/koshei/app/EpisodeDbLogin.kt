package koshei.app

import koshei.blocks.Db
import java.sql.Connection
import java.sql.DriverManager

/**
 * The login for the episode tables only (EpisodeStore, WatchStore; design §13, plan D-lite): the runtime role of
 * scripts/episode-db-roles.sql, while the rest of the worker keeps Db's login (the block registry, workflow_def).
 * KOSHEI_EPISODE_DB_USER / _PASS, else Db's own login (its override, KOSHEI_DB_USER / _PASS, its defaults). The URL is Db's.
 */
data class EpisodeDbLogin(val user: String, val pass: String) {
    fun connect(): Connection = DriverManager.getConnection(Db.url, user, pass)

    companion object {
        fun fromEnv(env: Map<String, String>): EpisodeDbLogin {
            val user = env["KOSHEI_EPISODE_DB_USER"]
            if (user != null) {
                val pass = requireNotNull(env["KOSHEI_EPISODE_DB_PASS"]) { "KOSHEI_EPISODE_DB_USER is set without KOSHEI_EPISODE_DB_PASS" }
                return EpisodeDbLogin(user, pass)
            }
            return EpisodeDbLogin(Db.user, Db.pass)   // exactly the rest of the worker's login (Db reads its override and env)
        }
    }
}
