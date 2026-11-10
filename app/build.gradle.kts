plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":runtime"))
    implementation(project(":registry"))
    implementation(project(":blocks"))
    implementation(project(":compiler"))
    implementation(project(":opcua"))        // conformance: ModelValidator/SiteModel/CommandPolicy/CanonicalSetpoints
    implementation(project(":delegation"))   // conformance: DelegationPolicy/DelegationPolicyValidator
    implementation(libs.temporal.sdk)
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
application { mainClass.set("yggdrasil.hermod.app.WorkerKt") }

// The Gradle daemon captures its environment at startup, so env vars exported AFTER the daemon started
// (e.g. HERMOD_PLUGIN_DIR / HERMOD_DB_* set by the gate script) would NOT reach forked task JVMs and the
// CLI-publish and worker could disagree on the plugin store dir / DB. Forward every HERMOD_* var from the
// live shell env into every entrypoint task (same rule as :authoring-api and :conductor-runtime), so the
// gate's exported env is authoritative and a newly read key cannot be missed by a hand-kept list.
// No-op for vars that are unset.
fun JavaExec.forwardHermodEnv() =
    System.getenv().filterKeys { it.startsWith("HERMOD_") }.forEach { (k, v) -> environment(k, v) }

tasks.named<JavaExec>("run") {
    forwardHermodEnv()
}
tasks.register<JavaExec>("starter") {
    group = "application"
    mainClass.set("yggdrasil.hermod.app.StarterKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardHermodEnv()
}
tasks.register<JavaExec>("cli") {
    group = "application"
    mainClass.set("yggdrasil.hermod.app.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardHermodEnv()
}
