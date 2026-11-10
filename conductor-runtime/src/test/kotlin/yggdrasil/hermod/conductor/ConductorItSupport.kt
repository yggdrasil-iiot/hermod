package yggdrasil.hermod.conductor

import com.netflix.conductor.client.http.ConductorClient
import yggdrasil.hermod.blocks.Db
import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.registry.Registry
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import java.sql.DriverManager
import java.time.Duration

/**
 * Shared, JVM-singleton container fixture for the Conductor e2e integration tests.
 *
 * Spins ONE `conductor-standalone` + ONE Postgres for the whole test JVM (started lazily on first access,
 * reused by every IT class — the next segment's approve/reject/compensation tests reuse this same object).
 * Postgres is wired into `Db.connect()` via [DbConnectionOverride]; the `comp_ledger` + `source_rows`/
 * `target_rows` DDL is hand-exec'd (no :app dep, so schema.sql isn't on this classpath).
 */
object ConductorItSupport {

    // Conductor standalone: Spring Boot starts in ~33s; health endpoint is /health (R-1).
    val conductor: GenericContainer<*> by lazy {
        GenericContainer("conductoross/conductor-standalone:3.15.0")
            .withExposedPorts(8080)
            .waitingFor(
                Wait.forHttp("/health")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(4))
            ).also { it.start() }
    }

    val postgres: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16")
            .withDatabaseName("hermod_test")
            .withUsername("hermod")
            .withPassword("hermod")
            .also { it.start() }
    }

    /** API base path the conductor-client points at (`/api` suffix). */
    val conductorApiUrl: String get() = "http://${conductor.host}:${conductor.getMappedPort(8080)}/api"

    private var initialized = false

    /** Start both containers, point Db.connect() at Postgres, create the schema. Idempotent. */
    @Synchronized
    fun ensureUp() {
        if (initialized) return
        // touch the lazies to start containers
        conductor
        postgres
        DbConnectionOverrideAdapter.point(postgres.jdbcUrl, postgres.username, postgres.password)
        exec(
            """
            CREATE TABLE IF NOT EXISTS source_rows (id text PRIMARY KEY, val text);
            CREATE TABLE IF NOT EXISTS target_rows (id text PRIMARY KEY, val text);
            CREATE TABLE IF NOT EXISTS comp_ledger (
              workflow_id text    NOT NULL,
              node_id     text    NOT NULL,
              block_id    text    NOT NULL,
              version     text    NOT NULL,
              bound_state jsonb   NOT NULL,
              compensated boolean NOT NULL DEFAULT false,
              outcome     text,
              at_millis   bigint,
              idx         int,
              PRIMARY KEY (workflow_id, node_id)
            );
            """.trimIndent()
        )
        initialized = true
    }

    fun newClient(): ConductorClient = ConductorClient.builder().basePath(conductorApiUrl).build()

    fun registry(): Registry = DispatchAssembly.buildRegistry(BlockIndex { Db.connect() }, BlockStore())

    fun ledger(): CompLedger = CompLedger { Db.connect() }

    fun exec(sql: String) =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().execute(sql)
        }

    fun count(table: String, where: String = "TRUE"): Int =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().executeQuery("SELECT count(*) FROM $table WHERE $where").use { rs ->
                rs.next(); rs.getInt(1)
            }
        }
}

/** Tiny adapter so the support object doesn't import the blocks override type at multiple call sites. */
private object DbConnectionOverrideAdapter {
    fun point(url: String, user: String, pass: String) {
        yggdrasil.hermod.blocks.DbConnectionOverride.url = url
        yggdrasil.hermod.blocks.DbConnectionOverride.user = user
        yggdrasil.hermod.blocks.DbConnectionOverride.pass = pass
    }
}
