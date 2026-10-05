plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}
rootProject.name = "koshei"
include("core", "registry", "blocks", "dispatch", "runtime", "app", "sdk", "compiler", "conductor-runtime", "authoring-api", "opcua", "delegation")
