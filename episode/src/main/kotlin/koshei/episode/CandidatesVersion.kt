package koshei.episode

/**
 * Bump when the projection rules change, so an approval taken on the old set reads as stale (design §9.3).
 * A change to a candidate's JSON shape changes the hash by itself; bump only when the same input would yield different
 * candidates.
 */
const val PROJECTION_VERSION = "1"

/**
 * "sha256:" + lowercase hex of sha256(JCS({ projectionVersion, candidates sorted by id })) — design §9.3,
 * contract 0.6 §3.2. Only koshei computes it; narrator echoes it back as `sawCandidatesVersion`. The snapshot is
 * deliberately NOT hashed: whether the world moved is REVALIDATING's question; this only answers "is the set of
 * options the approver saw the same?".
 */
fun candidatesVersion(candidates: List<Candidate>, projectionVersion: String = PROJECTION_VERSION): String {
    val root = mapper.createObjectNode().apply {
        put("projectionVersion", projectionVersion)
        putArray("candidates").apply { candidates.sortedBy { it.candidateId }.forEach { add(it.toJson()) } }
    }
    return "sha256:" + Jcs.sha256Hex(Jcs.canonical(root))
}
