plugins {
    `java-library`
}

dependencies {
    api("org.ow2.asm:asm:${property("asm.version")}")
    api("org.ow2.asm:asm-util:${property("asm.version")}")
    implementation("com.google.code.gson:gson:${property("gson.version")}")
    implementation("com.github.luben:zstd-jni:1.5.6-7")

    testImplementation("org.junit.jupiter:junit-jupiter:${property("junit.version")}")
    testImplementation("org.assertj:assertj-core:${property("assertj.version")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
