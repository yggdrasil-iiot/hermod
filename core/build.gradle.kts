plugins {
    kotlin("jvm")
    `maven-publish`
}
kotlin { jvmToolchain(21) }
tasks.test { useJUnitPlatform() }
group = "yggdrasil.hermod"
version = "0.2.0"
dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
}
publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}
