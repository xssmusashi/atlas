// atlas-mc — Fabric Loom integration. Stub for sub-plan 1; sub-plan 6 wires up worldgen.
// Loom + MC versions intentionally TBD until real integration starts (sub-plan 6),
// to avoid downloading 100s of MB of MC assets for a no-op stub.

plugins {
    `java-library`
}

dependencies {
    implementation(project(":atlas-core"))
}
