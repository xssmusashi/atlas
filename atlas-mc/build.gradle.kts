// atlas-mc — Fabric mod for Minecraft 26.1+ (unobfuscated, Mojang names everywhere).
// MC 26.1 uses the new net.fabricmc.fabric-loom plugin (no remapping needed).

plugins {
    id("net.fabricmc.fabric-loom")
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
}

val mcVersion        = "26.1.2"
val loaderVersion    = "0.19.2"
val fabricApiVersion = "0.146.1+26.1.2"

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // No mappings dependency — MC 26.1 is already unobfuscated.
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

    implementation(project(":atlas-core"))
    include(project(":atlas-core"))
}

loom {
    runs {
        named("client") { ideConfigGenerated(true) }
        named("server") { ideConfigGenerated(true) }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("--add-modules=jdk.incubator.vector")
}
