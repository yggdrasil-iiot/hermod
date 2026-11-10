package yggdrasil.hermod.registry

import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager

/**
 * Class-scoped (single) Testcontainers Postgres 16, started once and reused across tests.
 * `reset()` (in @BeforeTest) lazily starts the container, ensures the block_index table exists,
 * and TRUNCATEs it. The container stays warm; a JVM shutdown hook closes it at the end of the run.
 * REF: blocks/.../DbTestSupport.kt — same container idiom.
 */
object RegistryDbSupport {
    private val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    private var started = false

    /** Connection factory to inject into BlockIndex / BlockStore-using code. */
    fun connection(): Connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)

    @Synchronized
    fun reset() {
        if (!started) {
            container.start()
            // ensure schema via the same SQL the index ships
            BlockIndex(::connection).ensureSchema()
            Runtime.getRuntime().addShutdownHook(Thread { container.stop() })
            started = true
        }
        connection().use { it.createStatement().execute("TRUNCATE block_index") }
    }
}
