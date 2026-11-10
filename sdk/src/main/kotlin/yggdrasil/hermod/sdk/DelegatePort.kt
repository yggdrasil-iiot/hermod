package yggdrasil.hermod.sdk

/** One external-delegation request: which policy endpoint + the feature payload (a pass-through Record). */
data class DelegationRequest(val endpointId: String, val payload: Map<String, String?>)

/** Outcome of an external delegation call. `ok=false` = the call itself failed (transport/HTTP/parse). */
data class DelegationResult(val ok: Boolean, val score: Double?, val raw: String, val detail: String)

/**
 * The external-delegation boundary (R2 delegation seam). R2 = HTTP/REST to an external scorer;
 * blocks depend ONLY on this interface — the worker injects an [yggdrasil.hermod.delegation.HttpDelegatePort],
 * tests inject a fake. Mirrors [ApplyPort].
 */
interface DelegatePort {
    /** Call the resolved external endpoint with `payload`; return its numeric score (+ raw body). */
    fun call(req: DelegationRequest): DelegationResult
}
