plugins {
    kotlin("jvm")
    kotlin("plugin.spring")          // NO version — root manages the Kotlin plugin version (see note)
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":core"))
    implementation(project(":registry"))
    implementation(project(":compiler"))
    implementation(project(":dispatch"))
    implementation(project(":runtime")) {  // control plane: @Lazy Temporal EnginePort (run/status/approve/reject)
        exclude(group = "org.slf4j", module = "slf4j-simple")  // Spring Boot owns logging (Logback) in the edge; slf4j-simple would collide
    }
    implementation(libs.temporal.sdk)      // EngineConfig references WorkflowClient/WorkflowServiceStubs directly (mirrors :app)
    implementation(project(":conductor-runtime")) {  // edge holds BOTH engine clients (Temporal already present)
        exclude(group = "org.slf4j", module = "slf4j-simple")  // Spring Boot owns logging (Logback) in the edge; slf4j-simple would collide
    }
    implementation(libs.conductor.client)          // ConductorClient/Starter/Deployer types referenced directly (impl not transitive)
    implementation(project(":blocks"))     // for Db.connect() + builtin manifests on classpath
    implementation(project(":opcua")) {  // SiteModel + CommandPolicy + CanonicalSetpoints for the reconciliation pre-flight
        exclude(group = "org.slf4j", module = "slf4j-simple")  // Spring Boot owns logging (Logback) in the edge; slf4j-simple would collide
    }
    implementation(libs.tahu.core)         // outbound emit (spec 2026-07-01): SparkplugEdgeSession decodes rebirth NCMD directly (already in the catalog / :opcua)
    implementation(libs.anthropic.java)    // fsm-assist (R4): real AnthropicLlmAssistPort (structured outputs). Fake stays the default bean.
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.kotlin)
    implementation(libs.hikaricp)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(kotlin("test"))
}
application { mainClass.set("koshei.authoring.AuthoringApplicationKt") }

// The Gradle daemon captures its environment at startup, so env vars exported AFTER the daemon started
// (e.g. KOSHEI_PUBLISH_DISABLED / KOSHEI_BIND_ADDRESS set by an operator) would NOT reach the forked `run`
// JVM, silently leaving a switch like publish-disable inapplied. Forward every KOSHEI_* var from the live
// shell into the run task so the shell env is authoritative (mirrors `forwardKosheiEnv` in app/build.gradle.kts).
// No-op when unset.
tasks.named<JavaExec>("run") {
    System.getenv().filterKeys { it.startsWith("KOSHEI_") }.forEach { (k, v) -> environment(k, v) }
}
