# Atlas

Next-generation Minecraft worldgen for Fabric: tile-based pipeline, JIT-compiled DensityFunction (Vector API + ASM), parallel DAG scheduler. Targets ≥1500 chunks/second on a 16-core CPU.

**Status:** Phase 1 — bootstrap. Not yet usable in-game.

**Target:** Minecraft 26.1, Fabric loader, JDK 25.

**License:** Apache-2.0.

## Modules

- `atlas-core` — pure Java, no MC dependencies. DFC parser, JIT compiler, tile pipeline, biome lookup, region storage.
- `atlas-mc` — Fabric mod, MC integration glue.
- `atlas-bench` — JMH benchmarks.

## Build

```bash
./gradlew build
```

## Bench

```bash
./gradlew :atlas-bench:jmh
```

## Incompatible with

- **C2ME** — Atlas replaces C2ME's worldgen; running both will fail at startup.

## Design

See `docs/superpowers/specs/2026-04-20-atlas-design.md` for full design.
