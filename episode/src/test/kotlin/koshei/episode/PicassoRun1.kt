package koshei.episode

/** Verbatim lines of the picasso narrator handoff run-1 (copied into test resources; the picasso repo is read-only). */
object PicassoRun1 {
    private fun res(name: String): String =
        requireNotNull(javaClass.getResource("/picasso/run-1/$name")) { "missing test resource $name" }.readText(Charsets.UTF_8)

    val manifest: String get() = res("manifest.json")
    private val incidents: List<String> get() = res("incidents.jsonl").lines().filter { it.isNotBlank() }
    private val searches: List<String> get() = res("remedy-searches.jsonl").lines().filter { it.isNotBlank() }

    fun incident(id: String): String = incidents.single { mapper.readTree(it).text("incidentId") == id }
    fun search(id: String): String = searches.single { mapper.readTree(it).text("searchId") == id }

    fun snapshot(incidentIds: List<String> = emptyList(), searchIds: List<String> = emptyList()): Snapshot =
        Snapshot.parse(manifest, incidentIds.map(::incident), searchIds.map(::search))
}
