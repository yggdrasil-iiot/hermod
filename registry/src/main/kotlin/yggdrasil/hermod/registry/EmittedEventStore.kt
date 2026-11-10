package yggdrasil.hermod.registry

import java.sql.Connection

/** Write-once dedup ledger for the outbound governance-event surface (spec 2026-07-01).
 *  `open` so the emitter's unit tests can substitute an in-memory claim set (no DB). */
open class EmittedEventStore(private val connect: () -> Connection) {

    /** true only when THIS call inserted the row (exactly-once under concurrency). */
    open fun tryClaim(runId: String, eventType: String, atMillis: Long): Boolean =
        connect().use { c ->
            c.prepareStatement(
                "INSERT INTO emitted_event(run_id,event_type,emitted_at) VALUES(?,?,?) " +
                "ON CONFLICT (run_id,event_type) DO NOTHING"
            ).use { ps ->
                ps.setString(1, runId); ps.setString(2, eventType); ps.setLong(3, atMillis)
                ps.executeUpdate() == 1
            }
        }

    /** True if (runId,eventType) was already claimed — used by the emitter's ordering guard. */
    open fun claimed(runId: String, eventType: String): Boolean =
        connect().use { c ->
            c.prepareStatement("SELECT 1 FROM emitted_event WHERE run_id=? AND event_type=?").use { ps ->
                ps.setString(1, runId); ps.setString(2, eventType)
                ps.executeQuery().use { it.next() }
            }
        }

    /** Test-support only (production clears the ledger inline in RunStore.clearArchive, Task 1.2). */
    fun clearForRun(runId: String): Unit =
        connect().use { c ->
            c.prepareStatement("DELETE FROM emitted_event WHERE run_id=?").use { ps ->
                ps.setString(1, runId); ps.executeUpdate()
            }
        }
}
