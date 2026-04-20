// atlas-mc — Fabric mod that exposes Atlas worldgen to Minecraft.
// Sub-plan 6 wires Loom + a stub AtlasChunkGenerator that registers but throws on use.
// Sub-plan 7 fills in DfcBridge and the real generator implementation.

plugins {
    id("fabric-loom") version "1.9-SNAPSHOT"
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    maven("https://maven.parchmentmc.org") { name = "ParchmentMC" }
}

val mcVersion       = "1.21.4"
val yarnMappings    = "1.21.4+build.8"
val loaderVersion   = "0.16.10"
val fabricApiVersion = "0.119.2+1.21.4"

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    mappings("net.fabricmc:yarn:$yarnMappings:v2")
    modImplementation("net.fabricmc:fabric-loader:$loaderVersion")
    modImplementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion")

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
