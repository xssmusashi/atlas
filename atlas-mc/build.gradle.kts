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

    // atlas-core depends on zstd-jni; Loom's include(project) does NOT bundle
    // transitive deps, so we list zstd-jni explicitly. Without this, the .atr
    // serializer crashes with NoClassDefFoundError at runtime.
    implementation("com.github.luben:zstd-jni:1.5.6-7")
    include("com.github.luben:zstd-jni:1.5.6-7")

    // gson is also a transitive of atlas-core (DfcLoader); MC ships gson, but
    // bundle ours to guarantee the version we tested with.
    implementation("com.google.code.gson:gson:2.11.0")
    include("com.google.code.gson:gson:2.11.0")
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
