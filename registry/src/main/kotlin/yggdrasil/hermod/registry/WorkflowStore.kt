// registry/src/main/kotlin/hermod/registry/WorkflowStore.kt
package yggdrasil.hermod.registry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import yggdrasil.hermod.core.WorkflowDef
import java.sql.Connection

/**
 * Postgres store of operator-composed workflow defs, keyed by (name, version), immutable per key
 * (a change is a new version — preserves replay determinism, mirrors block_index). `connect` is injected
 * so the authoring-api can pool via Hikari and the worker can use Db.connect. Shared by both.
 */
class WorkflowStore(private val connect: () -> Connection) {
    data class SaveResult(val ok: Boolean, val error: String? = null)
    data class Row(val name: String, val version: String, val deployed: Boolean)

    private val mapper = jacksonObjectMapper()

    fun save(def: WorkflowDef, version: String): SaveResult = connect().use { c ->
        // Reject duplicate first for a clean message (PK conflict would also throw).
        c.prepareStatement("SELECT 1 FROM workflow_def WHERE name=? AND version=?").use { ps ->
            ps.setString(1, def.name); ps.setString(2, version)
            if (ps.executeQuery().next()) return SaveResult(false, "${def.name}@$version already exists (immutable)")
        }
        c.prepareStatement("INSERT INTO workflow_def(name,version,def_json,deployed) VALUES(?,?,?,true)").use { ps ->
            ps.setString(1, def.name); ps.setString(2, version); ps.setString(3, mapper.writeValueAsString(def))
            ps.executeUpdate()
        }
        SaveResult(true)
    }

    fun get(name: String, version: String): WorkflowDef? = connect().use { c ->
        c.prepareStatement("SELECT def_json FROM workflow_def WHERE name=? AND version=?").use { ps ->
            ps.setString(1, name); ps.setString(2, version)
            ps.executeQuery().use { rs -> if (rs.next()) mapper.readValue(rs.getString(1)) else null }
        }
    }

    fun list(): List<Row> = connect().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT name,version,deployed FROM workflow_def ORDER BY name,version").use { rs ->
                buildList { while (rs.next()) add(Row(rs.getString(1), rs.getString(2), rs.getBoolean(3))) }
            }
        }
    }

    fun listDeployed(): List<Pair<String, String>> = connect().use { c ->
        c.createStatement().use { st ->
            st.executeQuery("SELECT name,version FROM workflow_def WHERE deployed").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
            }
        }
    }
}
