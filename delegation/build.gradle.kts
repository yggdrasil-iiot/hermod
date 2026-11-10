plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":sdk"))
    implementation(libs.jackson.kotlin)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)   // cf. :opcua — only for the standalone SimMain process
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":registry"))   // ManifestsValidateTest — test scope only; main stays 0-registry
}
// Single source of truth: the repo-root model/ is the Git-canonical authority; the classpath copy is
// generated at build time (no committed duplicate, no drift). into("model") + exclude md are REQUIRED.
tasks.processResources {
    from(rootProject.file("model")) { into("model"); exclude("**/*.md") }
}
// Standalone scoring simulator for the gate/demo: ./gradlew :delegation:runSim
application { mainClass.set("yggdrasil.hermod.delegation.SimMainKt") }
// Forward every HERMOD_* var from the live shell into the forked JVM (same rule as `forwardHermodEnv` in
// app/build.gradle.kts): the Gradle daemon keeps the env it started with. No-op for vars that are unset.
fun JavaExec.fwd() =
    System.getenv().filterKeys { it.startsWith("HERMOD_") }.forEach { (k, v) -> environment(k, v) }
tasks.register<JavaExec>("runSim") {
    group = "application"; mainClass.set("yggdrasil.hermod.delegation.SimMainKt")
    classpath = sourceSets["main"].runtimeClasspath; fwd()
    workingDir = rootDir   // repo root so a relative HERMOD_DELEGATION_POLICY override resolves
}
