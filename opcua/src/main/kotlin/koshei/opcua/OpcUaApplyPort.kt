package koshei.opcua

import koshei.sdk.ApplyOutcome
import koshei.sdk.ApplyPort
import koshei.sdk.DoneClearMode
import koshei.sdk.ReadResult
import org.eclipse.milo.opcua.sdk.client.OpcUaClient
import org.eclipse.milo.opcua.sdk.client.api.identity.AnonymousProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.IdentityProvider
import org.eclipse.milo.opcua.sdk.client.api.identity.UsernameProvider
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * Milo OPC-UA client implementation of [ApplyPort].
 *
 * R1 mechanisms:
 *   - [write]: string→Double Variant, `writeValue`, confirm by numeric read-back equality.
 *   - [call]: write `true` to the trigger node (Boolean-trigger, NOT a UA Method call), then poll
 *     the done node for a `"true"` value (case-insensitive). Returns ok only on a confirmed
 *     rising edge; never ok on "accepted" alone. On `doneClear=ON_RELEASE` (the default) it then
 *     de-asserts the trigger so the equipment rearms the done bit for the next call; other
 *     `DoneClearMode`s are unimplemented in the R1 direct path and fail closed before any actuation.
 *
 * Read-back-mismatch failure path is not exercised by [OpcUaApplyPortIT] (impractical to induce on
 * a healthy loopback sim); it is covered by the Chunk-3 FakeApplyPort block test.
 */
class OpcUaApplyPort(private val endpoint: String) : ApplyPort, AutoCloseable {

    private lateinit var client: OpcUaClient

    fun connect(): OpcUaApplyPort {
        val policy = securityPolicyFor(System.getenv("KOSHEI_OPCUA_SECURITY") ?: "none")
        val identity = identityProviderFor(System.getenv("KOSHEI_OPCUA_USER"), System.getenv("KOSHEI_OPCUA_PASS"))
        println("[opcua] connecting to $endpoint (security=${policy.name}, identity=${identity::class.simpleName})")
        val c = OpcUaClient.create(
            endpoint,
            { endpoints ->
                Optional.ofNullable(
                    endpoints.firstOrNull { e -> e.securityPolicyUri == policy.uri }
                )
            },
            { configBuilder -> configBuilder.setIdentityProvider(identity).build() },
        )
        c.connect().get(30, TimeUnit.SECONDS)
        client = c
        return this
    }

    override fun read(nodeId: String): ReadResult {
        val dv: DataValue = client.readValue(0.0, TimestampsToReturn.Neither, NodeId.parse(nodeId)).get(10, TimeUnit.SECONDS)
        return ReadResult(
            value = dv.value?.value?.toString(),
            good = dv.statusCode?.isGood ?: false,
        )
    }

    override fun write(nodeId: String, type: String, value: String): ApplyOutcome {
        val jvmValue: Any = typeToVariant(type, value)
        val statusCode = client.writeValue(NodeId.parse(nodeId), DataValue(Variant(jvmValue))).get(10, TimeUnit.SECONDS)
        if (!statusCode.isGood) {
            return ApplyOutcome(ok = false, detail = "write to $nodeId failed: $statusCode")
        }
        val readBack = read(nodeId)
        if (!readBack.good) {
            return ApplyOutcome(ok = false, detail = "read-back from $nodeId returned bad status")
        }
        val match = numericallyEqual(value, readBack.value)
        return ApplyOutcome(
            ok = match,
            detail = if (match) "written+confirmed $value to $nodeId (read-back: ${readBack.value})"
                     else "read-back mismatch on $nodeId: wrote $value, got ${readBack.value}",
        )
    }

    override fun call(commandNodeId: String, doneNodeId: String, timeoutMs: Long,
                      doneClear: DoneClearMode): ApplyOutcome {
        // Fail closed BEFORE any actuation if we can't complete the handshake for this equipment.
        if (doneClear != DoneClearMode.ON_RELEASE) {
            return ApplyOutcome(ok = false, detail = "doneClear mode $doneClear not implemented in the R1 direct apply path")
        }
        // Capture the baseline FIRST: rising-edge confirm requires observing a false→true transition,
        // not merely "done is true now". A stale done=true (un-reset prior run) must NOT count as confirmed.
        val baseline = read(doneNodeId)
        if (baseline.value.equals("true", ignoreCase = true)) {
            return ApplyOutcome(ok = false, detail = "doneNode $doneNodeId already true before call — reset required (no rising edge)")
        }
        // Write `true` to the Boolean trigger node (R1 pinned mechanism — not UA Method call)
        val triggerStatus = client.writeValue(NodeId.parse(commandNodeId), DataValue(Variant(true))).get(10, TimeUnit.SECONDS)
        if (!triggerStatus.isGood) {
            return ApplyOutcome(ok = false, detail = "trigger write to $commandNodeId failed: $triggerStatus")
        }
        // Poll done node for rising-edge (false→true); never return ok on accepted-only.
        // Transient read errors (e.g. per-read timeout) are swallowed; the overall timeoutMs
        // deadline still governs when to give up.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val done = read(doneNodeId)
                if (done.value.equals("true", ignoreCase = true)) {
                    // Complete the handshake: release our own command output so the equipment rearms done for
                    // the next apply. Best-effort — the activation is already confirmed; a failed de-assert is
                    // logged in `detail` and will surface on the next call's fail-closed baseline guard.
                    // We write only the trigger (our output), never the equipment's done bit (often read-only).
                    val note = try {
                        val st = client.writeValue(NodeId.parse(commandNodeId), DataValue(Variant(false))).get(10, TimeUnit.SECONDS)
                        if (st.isGood) "" else " (warning: trigger de-assert not confirmed: $st)"
                    } catch (e: Exception) { " (warning: trigger de-assert failed: ${e.message})" }
                    return ApplyOutcome(ok = true, detail = "rising-edge confirmed on $doneNodeId$note")
                }
            } catch (_: Exception) {
                // transient read failure — keep polling until deadline
            }
            Thread.sleep(100)
        }
        return ApplyOutcome(
            ok = false,
            detail = "timeout (${timeoutMs}ms) waiting for rising edge on $doneNodeId",
        )
    }

    override fun close() {
        if (::client.isInitialized) {
            try { client.disconnect().get(10, TimeUnit.SECONDS) } catch (_: Exception) {}
        }
    }

    private fun typeToVariant(type: String, value: String): Any = when (type) {
        "Double" -> value.toDouble()
        else -> throw IllegalArgumentException("R1 unsupported OPC-UA type: $type")
    }

    /**
     * Numeric equality tolerant of "1500" vs "1500.0" — both parse to the same Double.
     * Falls back to string equality if either side is not a valid number.
     */
    private fun numericallyEqual(written: String, readBack: String?): Boolean {
        val w = written.toDoubleOrNull() ?: return written == readBack
        val r = readBack?.toDoubleOrNull() ?: return false
        return Math.abs(w - r) < 1e-9
    }

    companion object {
        /**
         * Resolve the client security policy from `KOSHEI_OPCUA_SECURITY` (default "none").
         * Only "none" is implemented (R1 + Ignition interop); cert-based (Basic256Sha256 +
         * client-cert trust exchange) is a documented follow-up. Fail closed for any other
         * value BEFORE connect/actuation.
         */
        fun securityPolicyFor(name: String): SecurityPolicy = when (name.lowercase()) {
            "none" -> SecurityPolicy.None
            else -> throw IllegalArgumentException(
                "KOSHEI_OPCUA_SECURITY='$name' not implemented (only 'none' is supported); " +
                    "cert-based (Basic256Sha256) is a documented follow-up")
        }

        /**
         * Resolve the client identity from `KOSHEI_OPCUA_USER` / `KOSHEI_OPCUA_PASS`. Both blank/unset
         * → [AnonymousProvider] (byte-identical to the pre-seam default). Some servers (e.g. Ignition's
         * OPC-UA server) grant anonymous READ but deny WRITE (`Bad_UserAccessDenied`) — authenticating
         * as a write-permitted user (Ignition default `opcuauser`) is required to actuate.
         */
        fun identityProviderFor(user: String?, pass: String?): IdentityProvider =
            if (!user.isNullOrBlank() && pass != null) UsernameProvider(user, pass)
            else {
                if (!user.isNullOrBlank() && pass == null)
                    println("[opcua] WARNING: KOSHEI_OPCUA_USER set but KOSHEI_OPCUA_PASS missing — falling back to anonymous (writes may be denied)")
                AnonymousProvider()
            }

        /**
         * The one OPC UA endpoint resolution shared by the worker ([default]), the CLI `fsm` commands and the
         * control plane's drift auto-correct: `KOSHEI_OPCUA_URL` if set, else the site model's `endpoint`
         * (`KOSHEI_OPCUA_MODEL` file, else the bundled `ot-site.yaml`). [model] is only invoked when the env
         * var is unset, so a bad `KOSHEI_OPCUA_MODEL` cannot fail the endpoint resolution itself when the URL is set.
         */
        fun defaultEndpoint(
            env: Map<String, String> = System.getenv(),
            model: () -> SiteModel = { SiteModel.default() },
        ): String = env["KOSHEI_OPCUA_URL"] ?: model().endpoint

        /**
         * Ambient factory (no-arg) for block use. Endpoint from [defaultEndpoint]. Mirrors the
         * `Db.connect()` pattern.
         */
        fun default(): OpcUaApplyPort = OpcUaApplyPort(defaultEndpoint()).connect()
    }
}
