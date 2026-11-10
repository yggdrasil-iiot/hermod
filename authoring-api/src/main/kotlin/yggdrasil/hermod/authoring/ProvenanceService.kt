package yggdrasil.hermod.authoring

import yggdrasil.hermod.opcua.ReconciliationProvenance

/** Verifies the ①-published canonical against its manifest by INDEPENDENTLY computing the content hash,
 *  and records the self-attested provenance. No git. */
open class ProvenanceService(private val canonical: CanonicalConfig) {
    sealed class Result {
        data class Ok(val defRef: String, val contentSha256: String) : Result()
        object Tampered : Result()        // bootDigest != manifest.contentSha256
        object Unresolvable : Result()    // missing bytes or missing manifest
    }
    open fun resolve(): Result {
        val digest = canonical.bootDigest ?: return Result.Unresolvable
        val m = canonical.manifest ?: return Result.Unresolvable
        if (m.contentSha256.isBlank() || m.defRef.isBlank()) return Result.Unresolvable
        if (digest != m.contentSha256) return Result.Tampered
        return Result.Ok(m.defRef, digest)
    }
    open fun record(runId: String, defRef: String, contentSha256: String) =
        ReconciliationProvenance.record(runId, defRef, contentSha256)
}
