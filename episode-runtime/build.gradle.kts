plugins {
    kotlin("jvm")
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
// The Temporal shell around the pure episode core (design §4.1): workflow, activity interfaces, the Mock narrator.
dependencies {
    implementation(project(":episode"))
    implementation(project(":runtime"))   // DataConverterSupport, RawJson
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.temporal.testing)
}
