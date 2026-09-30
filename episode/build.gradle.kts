plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    // `-Dkoshei.updateFixtures=true` regenerates the committed diagnosis-request fixtures (see DiagnosisRequestFixturesTest).
    systemProperty("koshei.updateFixtures", System.getProperty("koshei.updateFixtures") ?: "false")
}
// Pure core of the episode outer loop (design §4.1): no Temporal, no Spring, no JDBC — only Jackson's JSON tree.
dependencies {
    implementation(libs.jackson.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
