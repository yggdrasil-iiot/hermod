plugins {
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }
tasks.test {
    useJUnitPlatform()
    systemProperty("koshei.repoRoot", rootProject.projectDir.absolutePath)
    // R3's history generator runs only when asked: -Dkoshei.writeReplayHistories=true (design §7.5, plan D-lite).
    System.getProperty("koshei.writeReplayHistories")?.let { systemProperty("koshei.writeReplayHistories", it) }
    // The new set's directory name (default: today's date); an existing set is never overwritten.
    System.getProperty("koshei.replaySet")?.let { systemProperty("koshei.replaySet", it) }
}
// The Temporal shell around the pure episode core (design §4.1): workflow, activity interfaces, the Mock narrator.
dependencies {
    implementation(project(":episode"))
    implementation(project(":runtime"))   // DataConverterSupport, RawJson
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.snakeyaml)   // StrictYaml walks its events directly
    implementation(libs.postgresql)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
    testImplementation(libs.testcontainers.postgresql)
}
