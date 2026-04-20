pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
    // MC 26.1 ships unobfuscated — uses the new net.fabricmc.fabric-loom plugin.
    plugins {
        id("net.fabricmc.fabric-loom") version "1.16.1"
    }
}

rootProject.name = "atlas"

include("atlas-core")
include("atlas-bench")
include("atlas-mc")
