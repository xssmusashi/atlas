pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
    }
}

rootProject.name = "atlas"

include("atlas-core")
include("atlas-bench")
include("atlas-mc")
