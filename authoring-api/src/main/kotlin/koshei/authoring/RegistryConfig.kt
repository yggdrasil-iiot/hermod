package koshei.authoring

import com.zaxxer.hikari.HikariDataSource
import koshei.blocks.Db
import koshei.dispatch.DispatchAssembly
import koshei.registry.BlockIndex
import koshei.registry.BlockStore
import koshei.registry.Registry
import koshei.registry.RunStore
import koshei.registry.WorkflowStore
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
        poolName = "koshei-authoring"
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

    @Bean fun fsmDeploymentStore(ds: HikariDataSource): koshei.registry.FsmDeploymentStore = koshei.registry.FsmDeploymentStore { ds.connection }

    @Bean fun driftStore(ds: HikariDataSource): koshei.registry.DriftStore = koshei.registry.DriftStore { ds.connection }

    @Bean fun driftCorrectionStore(ds: HikariDataSource): koshei.registry.DriftCorrectionStore =
        koshei.registry.DriftCorrectionStore { ds.connection }

    @Bean fun compLedger(ds: HikariDataSource): koshei.conductor.CompLedger =
        koshei.conductor.CompLedger { ds.connection }

    @Bean fun sourceRowSeeder(ds: HikariDataSource): SourceRowSeeder = SourceRowSeeder(ds)

    @Bean fun canonicalConfig(): CanonicalConfig = CanonicalConfig.fromEnv()

    // Null when no canonical bytes are published (KOSHEI_RECIPE_SETPOINTS unset or unreadable): the API still
    // boots and ReconciliationController fail-closes 409 canonical-unresolvable. Bytes that ARE present but
    // invalid still fail boot (CanonicalSetpoints.parse is fail-closed). Parsed from the SAME bytes as bootDigest.
    @Bean fun canonicalSetpoints(cc: CanonicalConfig): koshei.opcua.CanonicalSetpoints? {
        if (cc.rawBytes == null) {
            log.warn("no published canonical (KOSHEI_RECIPE_SETPOINTS={}) — reconciliations will be rejected 409 canonical-unresolvable",
                cc.file?.path ?: "unset")
            return null
        }
        return koshei.opcua.CanonicalSetpoints.parse(cc.yamlText(), koshei.opcua.SiteModel.default(), koshei.opcua.CommandPolicy.default())
    }

    @Bean fun provenanceService(cc: CanonicalConfig): ProvenanceService = ProvenanceService(cc)
}
