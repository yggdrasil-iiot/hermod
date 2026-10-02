package koshei.episode.runtime

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/** One Postgres for the module's tests, started on first use; every test starts from empty episode tables. */
internal object EpisodeDb {
    private val container = PostgreSQLContainer("postgres:16")
    private var started = false

    fun connection(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    @Synchronized fun reset(): EpisodeStore {
        val store = EpisodeStore(::connection)
        if (!started) {
            container.start()
            store.ensureSchema()
            store.ensureSchema()   // the script must be safe to run twice (it runs at every worker start)
            started = true
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
        }
        connection().use { it.createStatement().execute("TRUNCATE episode_event, episode_notice, episode_dispatch, episode_watch_cursor, episode_watch_log") }
        return store
    }
}
