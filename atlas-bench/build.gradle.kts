plugins {
    id("me.champeau.jmh") version "0.7.2"
}

dependencies {
    jmh(project(":atlas-core"))
    jmh("org.openjdk.jmh:jmh-core:${property("jmh.version")}")
    jmhAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:${property("jmh.version")}")
}

jmh {
    warmupIterations.set(2)
    iterations.set(3)
    fork.set(1)
    timeOnIteration.set("3s")
    warmup.set("3s")
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
}
