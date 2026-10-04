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
val envKeys = listOf("KOSHEI_DB_URL", "KOSHEI_DB_USER", "KOSHEI_DB_PASS", "KOSHEI_WORKER_NAME", "KOSHEI_WF_POLL_MS", "CONDUCTOR_SERVER_URL")
fun JavaExec.fwd() = envKeys.forEach { k -> System.getenv(k)?.let { environment(k, it) } }
tasks.named<JavaExec>("run") { fwd() }
tasks.register<JavaExec>("ctl") {
    group = "application"; mainClass.set("koshei.conductor.ConductorCtlKt")
    classpath = sourceSets["main"].runtimeClasspath; fwd()
    // ConductorCtl resolves workflow YAML as `app/src/main/resources/workflows/<wf>.yaml`
    // relative to the working directory — must be the repo root, not the subproject root.
    workingDir = rootDir
}
