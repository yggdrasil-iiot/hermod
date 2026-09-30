plugins {
    `java-library`
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    // `-Dkoshei.updateFixtures=true` regenerates the committed diagnosis-request fixtures and then fails on purpose
    // (see DiagnosisRequestFixturesTest); rerun without it to verify.
    systemProperty("koshei.updateFixtures", System.getProperty("koshei.updateFixtures") ?: "false")
    systemProperty(
        "koshei.fixturesDir",
        layout.projectDirectory.dir("src/test/resources/contract/diagnosis/requests").asFile.absolutePath,
    )
}
// Pure core of the episode outer loop (design §4.1): no Temporal, no Spring, no JDBC — only Jackson's JSON tree.
// `api`: the public signatures (Snapshot, Candidate.toJson, DiagnosisRequest.toJson, ...) expose Jackson tree types.
dependencies {
    api(libs.jackson.databind)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
