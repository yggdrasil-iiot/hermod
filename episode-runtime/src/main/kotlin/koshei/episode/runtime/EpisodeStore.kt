package koshei.episode.runtime

import com.fasterxml.jackson.databind.JsonNode
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException

/** One stored record of an episode. [payloadJson] is the stored JSON value (jsonb's own spacing). */
data class StoredEvent(val seq: Long, val kind: String, val payloadJson: String)

/** A different record already holds this (instance, seq): never overwritten, never retried. */
class RecordConflict(message: String) : RuntimeException(message)

/** The database cannot hold this value (jsonb refuses NUL and lone surrogates): a retry would fail the same way. */
class RecordUnstorable(message: String, cause: Throwable) : RuntimeException(message, cause)

/**
 * The episode tables (design §8.5, §13). Plain JDBC, one connection per call. Open so tests can inject faults.
 */
open class EpisodeStore(private val connect: () -> Connection) {

    fun ensureSchema() {
        val sql = checkNotNull(javaClass.getResourceAsStream("/episode-schema.sql")) { "episode-schema.sql missing" }
            .bufferedReader().use { it.readText() }
        connect().use { c -> c.createStatement().use { it.execute(sql) } }
    }

    /**
     * Appends one record. True when written; false when the same record is already there (an activity retry);
     * [RecordConflict] when a different record holds the seq; [RecordUnstorable] when jsonb cannot hold the payload.
     *
     * "The same record" is the same kind and an equal JSON value (jsonb `=`), not the same text: key order and spacing do
     * not count, and neither does the form of a number (`1` and `1.0` are equal). jsonb keeps only the last of duplicated
     * keys, so callers pass a payload Jackson serialised, which has none.
     */
    open fun append(instanceId: String, seq: Long, kind: String, payloadJson: String): Boolean = storable("episode_event $instanceId #$seq") {
        appendRow(instanceId, seq, kind, payloadJson)
    }

    private fun appendRow(instanceId: String, seq: Long, kind: String, payloadJson: String): Boolean = connect().use { c ->
        val written = c.prepareStatement(
            "INSERT INTO episode_event (episode_instance_id, seq, kind, payload) VALUES (?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, instanceId); s.setLong(2, seq); s.setString(3, kind); s.setString(4, payloadJson)
            s.executeUpdate() == 1
        }
        if (written) return true
        c.prepareStatement(
            "SELECT kind = ? AND payload = ?::jsonb FROM episode_event WHERE episode_instance_id = ? AND seq = ?",
        ).use { s ->
            s.setString(1, kind); s.setString(2, payloadJson); s.setString(3, instanceId); s.setLong(4, seq)
            s.executeQuery().use { rs ->
                check(rs.next()) { "episode_event $instanceId #$seq vanished" }
                if (!rs.getBoolean(1)) throw RecordConflict("episode_event $instanceId #$seq already holds a different record")
            }
        }
        false
    }

    fun events(instanceId: String): List<StoredEvent> = connect().use { c ->
        c.prepareStatement("SELECT seq, kind, payload::text FROM episode_event WHERE episode_instance_id = ? ORDER BY seq").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) StoredEvent(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }.toList() }
        }
    }

    /** Appends one notice; [RecordUnstorable] when jsonb cannot hold it. */
    open fun notice(instanceId: String, noticeJson: String) = storable("episode_notice $instanceId") {
        connect().use { c -> insertNotice(c, instanceId, noticeJson) }
    }

    fun notices(instanceId: String): List<String> = connect().use { c ->
        c.prepareStatement("SELECT notice::text FROM episode_notice WHERE episode_instance_id = ? ORDER BY id").use { s ->
            s.setString(1, instanceId)
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }
    }

    /** The outcome already stored for this key, or null. */
    open fun outcome(key: String): DispatchOutcome? = connect().use { c ->
        c.prepareStatement("SELECT outcome_kind, answer FROM episode_dispatch WHERE idempotency_key = ?").use { s ->
            s.setString(1, key)
            s.executeQuery().use { rs -> if (rs.next()) DispatchOutcome(rs.getString(1), rs.getString(2)) else null }
        }
    }

    /**
     * Stores [outcome] unless the key already has one; returns whichever is stored (the first writer wins).
     * [RecordUnstorable], with nothing stored, when the database cannot hold the answer (text refuses a raw NUL).
     */
    open fun saveOutcome(instanceId: String, key: String, outcome: DispatchOutcome): DispatchOutcome {
        storable("episode_dispatch $key") { connect().use { c -> insertOutcome(c, instanceId, key, outcome) } }
        return checkNotNull(outcome(key)) { "episode_dispatch $key vanished" }
    }

    /**
     * Issues a person task exactly once (design §8.4): the task row and its notice are written in one transaction, and
     * nothing is written when the key already has a task. [RecordUnstorable], with neither row written, when jsonb
     * cannot hold the intent. [intentJson] must be valid JSON: the caller parses it first (the activity's dispatch does).
     */
    open fun issueTask(instanceId: String, key: String, intentJson: String): DispatchOutcome {
        val task = DispatchOutcome("PERSON_TASK")
        storable("episode_dispatch $key") {
            connect().use { c ->
                c.autoCommit = false
                try {
                    if (insertOutcome(c, instanceId, key, task)) {
                        val notice = strictJson.createObjectNode().apply {
                            put("kind", "PERSON_TASK")
                            put("idempotencyKey", key)
                            set<JsonNode>("intent", strictJson.readTree(intentJson))
                        }
                        insertNotice(c, instanceId, notice.toString())
                    }
                    c.commit()
                } catch (e: Throwable) {
                    // A failed rollback leaves the transaction open: re-enabling autocommit would commit it (pgjdbc), so
                    // the connection is left as it is and close() discards the transaction.
                    try { c.rollback() } catch (r: SQLException) { e.addSuppressed(r); throw e }
                    try { c.autoCommit = true } catch (r: SQLException) { e.addSuppressed(r) }
                    throw e
                }
                c.autoCommit = true   // a pooled connection must not come back mid-transaction
            }
        }
        return checkNotNull(outcome(key)) { "episode_dispatch $key vanished" }
    }

    /**
     * A data exception (SQLState class 22: NUL in jsonb, an invalid escape, a value out of range) is about the value,
     * so a retry fails the same way: it becomes [RecordUnstorable], which the activity reports as non-retryable.
     *
     * The message holds the SQLState and the server's primary message only. The server's Detail and Where quote the
     * value (an operator's note, an approver's id), and this message travels into the RECORD_LAG notice and the log;
     * the full exception stays the cause.
     */
    private inline fun <T> storable(what: String, block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.sqlState?.startsWith("22") == true) {
            val primary = (e as? PSQLException)?.serverErrorMessage?.message ?: e.sqlState
            throw RecordUnstorable("$what cannot be stored (SQLState ${e.sqlState}): $primary", e)
        }
        throw e
    }

    private fun insertOutcome(c: Connection, instanceId: String, key: String, outcome: DispatchOutcome): Boolean =
        c.prepareStatement(
            "INSERT INTO episode_dispatch (idempotency_key, episode_instance_id, outcome_kind, answer) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
        ).use { s ->
            s.setString(1, key); s.setString(2, instanceId); s.setString(3, outcome.kind); s.setString(4, outcome.answerJson)
            s.executeUpdate() == 1
        }

    private fun insertNotice(c: Connection, instanceId: String, noticeJson: String) {
        c.prepareStatement("INSERT INTO episode_notice (episode_instance_id, notice) VALUES (?, ?::jsonb)").use { s ->
            s.setString(1, instanceId); s.setString(2, noticeJson)
            s.executeUpdate()
        }
    }
}
