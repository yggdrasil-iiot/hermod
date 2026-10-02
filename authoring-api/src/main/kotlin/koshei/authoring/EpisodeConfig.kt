package koshei.authoring

import com.zaxxer.hikari.HikariDataSource
import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.episode.runtime.EpisodeReader
import koshei.episode.runtime.EpisodeStore
import koshei.runtime.DataConverterSupport
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** The episode control plane's beans. Lazy like EngineConfig: the context starts without Temporal or the database. */
@Configuration
class EpisodeConfig {
    @Bean fun episodeGateway(): EpisodeGateway =
        EpisodeGateway(lazy { WorkflowClient.newInstance(WorkflowServiceStubs.newLocalServiceStubs(), DataConverterSupport.clientOptions()) })

    /** Read only (R14): the episode worker creates and writes the tables (design §13). */
    @Bean fun episodeReader(ds: HikariDataSource): EpisodeReader = EpisodeStore { ds.connection }
}
