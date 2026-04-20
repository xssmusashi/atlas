# Atlas

Next-generation Minecraft worldgen for Fabric: tile-based pipeline, JIT-compiled DensityFunction (Vector API + ASM), parallel DAG scheduler. Targets ≥1500 chunks/second on a 16-core CPU.

**Status:** Phase 1 — sub-plan 2 (arithmetic + control nodes). Not yet usable in-game.

## Phase 1 progress

- ✅ Sub-plan 1 (`v0.1.0-bootstrap`): multi-module project, Constant JIT, JMH baseline.
- ✅ Sub-plan 2 (`v0.2.0-arithmetic`): positional + arithmetic + control nodes, **24.7× JIT speedup** on a 15-op tree (honest loop bench).
- ✅ Sub-plan 3 (`v0.3.0-noise`): Perlin + OctavePerlin nodes, **3.34M noise samples/sec** on worldgen-shaped tree (18 octaves + clamp). JIT speedup is modest (1.22×) here because perlin compute dominates dispatch — real wins land with parallel tile pipeline.
- ✅ Sub-plan 4 (`v0.4.0-tile`): Tile pipeline (8×8 chunks, off-heap MemorySegment) + work-stealing DagScheduler with bounded backpressure. **172 cps noise-only @ p=8**, near-linear scaling (5.93× of p=1).
- ⏳ Sub-plan 5: Region storage (.atr format).
- ⏳ Sub-plan 6: Real Fabric Loom integration + ChunkGenerator.
- ⏳ Sub-plan 7: Phase 1 acceptance bench (≥1500 cps end-to-end vs C2ME).

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
