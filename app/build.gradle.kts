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
    implementation(project(":episode-runtime"))   // the episode outer loop's worker (design §4.1)
    implementation(libs.temporal.sdk)
    implementation(libs.jackson.kotlin)   // episode CLI reads the picasso export's manifest and lines
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
application { mainClass.set("koshei.app.WorkerKt") }

// The Gradle daemon captures its environment at startup, so env vars exported AFTER the daemon started
// (e.g. KOSHEI_PLUGIN_DIR / KOSHEI_DB_* set by the gate script) would NOT reach forked task JVMs and the
// CLI-publish and worker could disagree on the plugin store dir / DB. Forward these from the live shell
// env into every entrypoint task so the gate's exported env is authoritative. No-op when unset.
val kosheiEnvKeys = listOf("KOSHEI_PLUGIN_DIR", "KOSHEI_DB_URL", "KOSHEI_DB_USER", "KOSHEI_DB_PASS", "KOSHEI_EPISODE_DB_USER", "KOSHEI_EPISODE_DB_PASS", "KOSHEI_WORKER_NAME", "KOSHEI_WORKFLOWS_DIR", "KOSHEI_WF_POLL_MS", "KOSHEI_FAULT_INJECT", "KOSHEI_TEST_BLOCKS", "KOSHEI_OPCUA_URL", "KOSHEI_OPCUA_MODEL", "KOSHEI_OPCUA_SECURITY", "KOSHEI_OPCUA_USER", "KOSHEI_OPCUA_PASS", "KOSHEI_DELEGATION_POLICY", "KOSHEI_DELEGATION_URL", "KOSHEI_APPLY_MODE", "KOSHEI_MQTT_URL", "KOSHEI_SPB_GROUP", "KOSHEI_SPB_EDGE", "KOSHEI_EPISODE_POLICY", "KOSHEI_NARRATOR", "KOSHEI_PICASSO", "KOSHEI_PICASSO_URL", "KOSHEI_PICASSO_AGENT_ID", "KOSHEI_PICASSO_TIMEOUT_MS", "KOSHEI_WATCH_EXPORTS", "KOSHEI_WATCH_CARRY", "KOSHEI_WATCH_INTERVAL_MS")
fun JavaExec.forwardKosheiEnv() = kosheiEnvKeys.forEach { k -> System.getenv(k)?.let { environment(k, it) } }

tasks.named<JavaExec>("run") {
    forwardKosheiEnv()
    // The run task's working directory is app/, so the default relative policy path would miss the repo's model/.
    if (System.getenv("KOSHEI_EPISODE_POLICY") == null) {
        environment("KOSHEI_EPISODE_POLICY", rootProject.file("model/episode-policy/active.yaml").absolutePath)
    }
}
tasks.register<JavaExec>("watcher") {
    group = "application"
    mainClass.set("koshei.app.WatcherKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKosheiEnv()
    // As for run: the working directory is app/, so the default relative policy path would miss the repo's model/.
    if (System.getenv("KOSHEI_EPISODE_POLICY") == null) {
        environment("KOSHEI_EPISODE_POLICY", rootProject.file("model/episode-policy/active.yaml").absolutePath)
    }
}
tasks.register<JavaExec>("starter") {
    group = "application"
    mainClass.set("koshei.app.StarterKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKosheiEnv()
}
tasks.register<JavaExec>("cli") {
    group = "application"
    mainClass.set("koshei.app.CliKt")
    classpath = sourceSets["main"].runtimeClasspath
    forwardKosheiEnv()
}
