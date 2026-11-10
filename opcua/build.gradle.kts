plugins {
    kotlin("jvm")
    application
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
dependencies {
    implementation(project(":sdk"))
    implementation(libs.milo.sdk.client)
    implementation(libs.milo.sdk.server)
    implementation(libs.tahu.core) {
        // tahu-core drags in logback-classic as a second SLF4J provider next to slf4j-simple below;
        // its classes only use the SLF4J API, so drop Logback (and the TRACE-root logback.xml it would pick up).
        exclude(group = "ch.qos.logback")
    }
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.yaml)
    implementation(libs.postgresql)
    implementation(libs.slf4j.simple)   // cf. :conductor-runtime — keep Logback off this module's runtime (runSim standalone process)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":registry"))   // for ManifestsValidateTest — test scope only; keeps main 0-registry
}
// Single source of truth: the repo-root model/ is the Git-canonical authority; the classpath copy
// is generated at build time (no committed duplicate, no drift). Runtime reads the baked classpath
// copy; HERMOD_OPCUA_MODEL/POLICY env can override at deploy.
tasks.processResources {
    from(rootProject.file("model")) { into("model"); exclude("**/*.md") }   // model YAML/JSON only; docs (README) stay out of the jar
}

// Standalone Milo simulator for the gate/demo (cf. :conductor-runtime run/ctl tasks).
application { mainClass.set("yggdrasil.hermod.opcua.SimMainKt") }
// Forward every HERMOD_* var from the live shell into the forked JVMs (same rule as `forwardHermodEnv` in
// app/build.gradle.kts): the Gradle daemon keeps the env it started with. No-op for vars that are unset.
fun JavaExec.fwd() =
    System.getenv().filterKeys { it.startsWith("HERMOD_") }.forEach { (k, v) -> environment(k, v) }
tasks.register<JavaExec>("runSim") {
    group = "application"; mainClass.set("yggdrasil.hermod.opcua.SimMainKt")
    classpath = sourceSets["main"].runtimeClasspath; fwd()
    workingDir = rootDir   // repo root so a relative HERMOD_OPCUA_MODEL/POLICY override resolves; the default load is the classpath resource
}

// Integration-PoV gate helper: write one Double value to the running sim out-of-band (drift injection).
// PerturbMain lives in the test source set so :opcua/src/main stays untouched (R1 boundary).
// args are resolved in doFirst (execution time) so configuring this task never fails a plain build that
// passes no -Pnode/-Pvalue.
tasks.register<JavaExec>("perturb") {
    group = "verification"
    description = "Write one Double value to the running OPC-UA sim out-of-band (gate drift injection)."
    mainClass.set("yggdrasil.hermod.opcua.PerturbMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    fwd()
    doFirst {
        args(
            project.findProperty("node") as String? ?: error("-Pnode required"),
            project.findProperty("value") as String? ?: error("-Pvalue required"),
        )
    }
}

// R4 FSM gate helper (run-fsm-gate.sh): read live state + govern + print ALLOW/DENY (no dispatch).
// Test source set keeps :opcua/src/main untouched (cf. perturb/PerturbMain).
tasks.register<JavaExec>("fsmGovern") {
    group = "verification"
    description = "Read the live equipment state, run TransitionGovernor, print ALLOW/DENY."
    mainClass.set("yggdrasil.hermod.opcua.FsmGovernMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootDir
    fwd()
    doFirst {
        args(
            project.findProperty("fsm") as String? ?: error("-Pfsm=<fsmFile> required"),
            project.findProperty("command") as String? ?: error("-Pcommand=<command> required"),
        )
    }
}

// Outbound-emit gate helper (run-outbound-emit-gate.sh): a Paho+Tahu Sparkplug probe that either
// CAPTURES the NBIRTH/NDATA order or PUBLISHES a rebirth NCMD — so the gate needs no external MQTT
// client (the environment has no mosquitto). Test source set (EmitProbeMain) keeps :opcua/src/main
// untouched (cf. perturb/PerturbMain); pure gate tooling, never referenced by any manifest/registry.
//   capture: ./gradlew :opcua:emitProbe -Pmode=capture -PmqttUrl=... -Pgrp=Hermod -Pedg=Governance -Pout=<file> -Psecs=900
//   rebirth: ./gradlew :opcua:emitProbe -Pmode=rebirth -PmqttUrl=... -Pgrp=Hermod -Pedg=Governance
tasks.register<JavaExec>("emitProbe") {
    group = "verification"
    description = "Sparkplug probe for the outbound-emit gate: -Pmode=capture|rebirth."
    mainClass.set("yggdrasil.hermod.opcua.emit.EmitProbeMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    workingDir = rootDir
    fwd()
    doFirst {
        val mode = project.findProperty("mode") as String? ?: error("-Pmode=capture|rebirth required")
        val url  = project.findProperty("mqttUrl") as String? ?: "tcp://localhost:1883"
        val grp  = project.findProperty("grp") as String? ?: "Hermod"
        val edg  = project.findProperty("edg") as String? ?: "Governance"
        val a = mutableListOf(mode, url, grp, edg)
        if (mode == "capture") {
            a.add(project.findProperty("out") as String? ?: error("-Pout=<file> required for capture"))
            a.add(project.findProperty("secs") as String? ?: "60")
        }
        args(a)
    }
}
