package koshei.episode

/** Verbatim lines of one picasso narrator-handoff run (copied into test resources; the picasso repo is read-only). */
class PicassoRun(private val run: Int) {
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-$run/$name")) { "missing test resource run-$run/$name" }
            .readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")
    private val incidents: List<String> get() = res("incidents.jsonl").lines().filter { it.isNotBlank() }
    private val searches: List<String> get() = res("remedy-searches.jsonl").lines().filter { it.isNotBlank() }
    val searchCount: Int get() = searches.size

    fun incident(id: String): String = incidents.single { mapper.readTree(it).text("incidentId") == id }
    fun search(id: String): String = searches.single { mapper.readTree(it).text("searchId") == id }

    fun snapshot(incidentIds: List<String> = emptyList(), searchIds: List<String> = emptyList()): Snapshot =
        Snapshot.parse(manifest, incidentIds.map(::incident), searchIds.map(::search))
}

/** Run 1, which the four contract fixtures and most unit tests use. */
val PicassoRun1 = PicassoRun(1)
