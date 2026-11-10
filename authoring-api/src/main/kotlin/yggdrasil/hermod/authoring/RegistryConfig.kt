package yggdrasil.hermod.authoring

import com.zaxxer.hikari.HikariDataSource
import yggdrasil.hermod.blocks.Db
import yggdrasil.hermod.dispatch.DispatchAssembly
import yggdrasil.hermod.registry.BlockIndex
import yggdrasil.hermod.registry.BlockStore
import yggdrasil.hermod.registry.Registry
import yggdrasil.hermod.registry.RunStore
import yggdrasil.hermod.registry.WorkflowStore
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RegistryConfig {
    private val log = org.slf4j.LoggerFactory.getLogger(RegistryConfig::class.java)

    @Bean(destroyMethod = "close")   // explicit: shut the pool down on context close
    fun dataSource(): HikariDataSource = HikariDataSource().apply {
        jdbcUrl = Db.url
        username = Db.user
        password = Db.pass
        poolName = "hermod-authoring"
        maximumPoolSize = 5
        // Keep the Spring context DB-free at startup: do not open a connection at
        // construction time. minimumIdle=0 means no idle connections are pre-created,
        // and initializationFailTimeout=-1 disables the startup connectivity probe so
        // the pool connects lazily on first getConnection().
        minimumIdle = 0
        initializationFailTimeout = -1
    }

    @Bean fun blockIndex(ds: HikariDataSource): BlockIndex = BlockIndex { ds.connection }
    @Bean fun blockStore(): BlockStore = BlockStore()
    @Bean fun registry(index: BlockIndex, store: BlockStore): Registry =
        DispatchAssembly.buildRegistry(index, store)

    @Bean fun workflowStore(ds: HikariDataSource): WorkflowStore = WorkflowStore { ds.connection }

    @Bean fun runStore(ds: HikariDataSource): RunStore = RunStore { ds.connection }

    @Bean fun fsmDeploymentStore(ds: HikariDataSource): yggdrasil.hermod.registry.FsmDeploymentStore = yggdrasil.hermod.registry.FsmDeploymentStore { ds.connection }

    @Bean fun driftStore(ds: HikariDataSource): yggdrasil.hermod.registry.DriftStore = yggdrasil.hermod.registry.DriftStore { ds.connection }

    @Bean fun driftCorrectionStore(ds: HikariDataSource): yggdrasil.hermod.registry.DriftCorrectionStore =
        yggdrasil.hermod.registry.DriftCorrectionStore { ds.connection }

    @Bean fun compLedger(ds: HikariDataSource): yggdrasil.hermod.conductor.CompLedger =
        yggdrasil.hermod.conductor.CompLedger { ds.connection }

    @Bean fun sourceRowSeeder(ds: HikariDataSource): SourceRowSeeder = SourceRowSeeder(ds)

    @Bean fun canonicalConfig(): CanonicalConfig = CanonicalConfig.fromEnv()

    // Null when no canonical bytes are published (HERMOD_RECIPE_SETPOINTS unset or unreadable): the API still
    // boots and ReconciliationController fail-closes 409 canonical-unresolvable. Bytes that ARE present but
    // invalid still fail boot (CanonicalSetpoints.parse is fail-closed). Parsed from the SAME bytes as bootDigest.
    @Bean fun canonicalSetpoints(cc: CanonicalConfig): yggdrasil.hermod.opcua.CanonicalSetpoints? {
        if (cc.rawBytes == null) {
            log.warn("no published canonical (HERMOD_RECIPE_SETPOINTS={}) — reconciliations will be rejected 409 canonical-unresolvable",
                cc.file?.path ?: "unset")
            return null
        }
        return yggdrasil.hermod.opcua.CanonicalSetpoints.parse(cc.yamlText(), yggdrasil.hermod.opcua.SiteModel.default(), yggdrasil.hermod.opcua.CommandPolicy.default())
    }

    @Bean fun provenanceService(cc: CanonicalConfig): ProvenanceService = ProvenanceService(cc)
}
