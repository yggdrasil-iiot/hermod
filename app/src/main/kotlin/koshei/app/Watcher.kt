package koshei.app

import io.temporal.client.WorkflowClient
import io.temporal.serviceclient.WorkflowServiceStubs
import koshei.blocks.Db
import koshei.episode.runtime.EpisodeStore
import koshei.episode.runtime.EpisodeWatcher
import koshei.episode.runtime.PicassoMode
import koshei.episode.runtime.PolicyFileReader
import koshei.episode.runtime.TemporalEpisodeSignals
import koshei.episode.runtime.WatchStore
import koshei.runtime.DataConverterSupport
import java.io.File
import java.nio.file.Path

/** The watcher's settings (design §12), read once at start. */
data class WatcherConfig(val exports: List<Path>, val carry: Path?, val intervalMs: Long, val policyPath: Path) {
    companion object {
        fun fromEnv(env: Map<String, String>): WatcherConfig {
            require(PicassoMode.fromEnv(env) != PicassoMode.OFF) {
                "KOSHEI_PICASSO is off: no episode worker runs, so the watcher would move its cursor past symptoms nobody opens"
            }
            val exports = requireNotNull(env["KOSHEI_WATCH_EXPORTS"]) { "KOSHEI_WATCH_EXPORTS: the picasso export directories, separated by '${File.pathSeparator}'" }
                .split(File.pathSeparator)
            require(exports.isNotEmpty() && exports.none { it.isBlank() }) { "KOSHEI_WATCH_EXPORTS has an empty entry: '${env["KOSHEI_WATCH_EXPORTS"]}'" }
            val carry = env["KOSHEI_WATCH_CARRY"]?.also { require(it.isNotBlank()) { "KOSHEI_WATCH_CARRY is blank" } }
            val interval = (env["KOSHEI_WATCH_INTERVAL_MS"] ?: "2000").toLongOrNull()
                ?.takeIf { it in 100..60_000 } ?: throw IllegalArgumentException("KOSHEI_WATCH_INTERVAL_MS must be 100..60000, was '${env["KOSHEI_WATCH_INTERVAL_MS"]}'")
            return WatcherConfig(
                exports.map { Path.of(it) }, carry?.let { Path.of(it) }, interval,
                Path.of(env["KOSHEI_EPISODE_POLICY"] ?: "model/episode-policy/active.yaml"),
            )
        }
    }
}

/**
 * The watcher process (design §4.2, §12): polls the exports and the carry, sends to Temporal on koshei's converter,
 * keeps its cursor in Postgres. It creates the episode tables if the worker has not yet (the script is idempotent).
 */
fun main() {
    val config = WatcherConfig.fromEnv(System.getenv())
    EpisodeStore { Db.connect() }.ensureSchema()
    val service = WorkflowServiceStubs.newLocalServiceStubs()
    val client = WorkflowClient.newInstance(service, DataConverterSupport.clientOptions())
    val watcher = EpisodeWatcher(config.exports, config.carry, PolicyFileReader(config.policyPath), WatchStore { Db.connect() }, TemporalEpisodeSignals(client))
    println("koshei watcher: exports=${config.exports.map { it.toAbsolutePath() }} carry=${config.carry?.toAbsolutePath() ?: "-"} " +
        "every ${config.intervalMs} ms (log: episode_watch_log)")
    Runtime.getRuntime().addShutdownHook(Thread { service.shutdown() })
    while (true) {
        try {
            val r = watcher.pollOnce()
            if (r.opened > 0 || r.evidence > 0) println("koshei watcher: opened ${r.opened}, evidence ${r.evidence}")
        } catch (e: Exception) {
            // The database or the file system failed mid-pass: nothing past the last cursor row is lost; try again.
            System.err.println("koshei watcher: pass failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        Thread.sleep(config.intervalMs)
    }
}
