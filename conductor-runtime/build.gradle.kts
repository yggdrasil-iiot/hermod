plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":registry"))
    implementation(project(":blocks"))
    implementation(project(":dispatch"))
    implementation(project(":sdk"))
    implementation(project(":compiler"))
    implementation(libs.conductor.client)
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
application { mainClass.set("koshei.conductor.ConductorWorkerMainKt") }
// The Gradle daemon keeps the env it started with; forward every KOSHEI_* var plus CONDUCTOR_SERVER_URL from
// the live shell into the forked JVMs (same rule as `forwardKosheiEnv` in app/build.gradle.kts). The worker
// polls every registry block (opcua.write / opcua.call / delegate.score ...), so it needs the same OT keys as
// the Temporal worker. No-op for vars that are unset.
fun JavaExec.fwd() = System.getenv()
    .filterKeys { it.startsWith("KOSHEI_") || it == "CONDUCTOR_SERVER_URL" }
    .forEach { (k, v) -> environment(k, v) }
tasks.named<JavaExec>("run") { fwd() }
tasks.register<JavaExec>("ctl") {
    group = "application"; mainClass.set("koshei.conductor.ConductorCtlKt")
    classpath = sourceSets["main"].runtimeClasspath; fwd()
    // ConductorCtl resolves workflow YAML as `app/src/main/resources/workflows/<wf>.yaml`
    // relative to the working directory — must be the repo root, not the subproject root.
    workingDir = rootDir
}
