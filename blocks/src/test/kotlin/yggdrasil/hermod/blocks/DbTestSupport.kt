package yggdrasil.hermod.blocks

import org.testcontainers.containers.PostgreSQLContainer

/**
 * Class-scoped (single) Testcontainers Postgres 16, started once and reused across tests.
 * `startAndReset()` (in @BeforeTest) is the per-test reset: it lazily starts the container,
 * ensures the tables exist, and TRUNCATEs them. `stop()` is intentionally a no-op so the
 * container stays warm; a JVM shutdown hook closes it at the end of the test run.
 */
object DbTestSupport {
    private val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    private var started = false

    @Synchronized
    fun startAndReset() {
        if (!started) {
            container.start()
            DbConnectionOverride.url = container.jdbcUrl
            DbConnectionOverride.user = container.username
            DbConnectionOverride.pass = container.password
            exec("CREATE TABLE IF NOT EXISTS source_rows (id TEXT PRIMARY KEY, val TEXT)")
            exec("CREATE TABLE IF NOT EXISTS target_rows (id TEXT PRIMARY KEY, val TEXT)")
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            started = true
        }
        exec("TRUNCATE source_rows")
        exec("TRUNCATE target_rows")
    }

    /** Intentionally a no-op: the class-scoped container stays warm across tests. */
    fun stop() { /* container closed by JVM shutdown hook */ }

    fun exec(sql: String) = Db.connect().use { it.createStatement().execute(sql) }

    fun queryVal(id: String): String? =
        Db.connect().use { c ->
            c.prepareStatement("SELECT val FROM target_rows WHERE id=?").use { ps ->
                ps.setString(1, id)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
            }
        }

    fun count(table: String = "target_rows"): Int =
        Db.connect().use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM $table").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
}
