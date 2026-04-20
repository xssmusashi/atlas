# Atlas

Next-generation Minecraft worldgen for Fabric: tile-based pipeline, JIT-compiled DensityFunction (Vector API + ASM), parallel DAG scheduler. Targets ≥1500 chunks/second on a 16-core CPU.

**Status:** Phase 1 v1.0.0 — foundation complete. atlas-core production-quality (JIT, tile pipeline, region storage, scheduler), atlas-mc scaffolded against MC 1.21.4 + Fabric API. Real in-game ChunkGenerator integration is Phase 2 (requires live MC testing + remapJar fix).

See `docs/superpowers/specs/2026-04-20-atlas-phase1-acceptance.md` for the full acceptance report.

## Phase 1 progress

- ✅ Sub-plan 1 (`v0.1.0-bootstrap`): multi-module project, Constant JIT, JMH baseline.
- ✅ Sub-plan 2 (`v0.2.0-arithmetic`): positional + arithmetic + control nodes, **24.7× JIT speedup** on a 15-op tree (honest loop bench).
- ✅ Sub-plan 3 (`v0.3.0-noise`): Perlin + OctavePerlin nodes, **3.34M noise samples/sec** on worldgen-shaped tree (18 octaves + clamp). JIT speedup is modest (1.22×) here because perlin compute dominates dispatch — real wins land with parallel tile pipeline.
- ✅ Sub-plan 4 (`v0.4.0-tile`): Tile pipeline (8×8 chunks, off-heap MemorySegment) + work-stealing DagScheduler with bounded backpressure. **172 cps noise-only @ p=8**, near-linear scaling (5.93× of p=1).
- ✅ Sub-plan 5 (`v0.5.0-region`): `.atr` binary region format (16×16 tiles per region, header+index+zstd payloads), atomic tmp+rename, crash-safe via offsets-last-write. **45 ms serialize / 82 ms write+fsync** per 48 MB tile (zstd-3, 1.05× ratio on raw doubles — quantization comes in later).
- ✅ Sub-plan 6 (`v0.6.0-mc`): Real Fabric Loom 1.9.2 + MC 1.21.4 + Fabric API + Loader. AtlasMod ModInitializer with C2MEDetector (fail-fast on coexistence). **Vector API emitter** (jdk.incubator.vector) for arithmetic-only trees: **2.49× speedup over scalar JIT** on 16×16 slice. JitOptions(SCALAR/VECTOR/AUTO) with auto-fallback to scalar for noise trees.
- ✅ Sub-plan 7 (`v1.0.0`): **AtlasService public API** for other Fabric mods (Farsight integration ready). DfcBridge skeleton with registry-based dispatch. Phase 1 **acceptance report** in `docs/superpowers/specs/2026-04-20-atlas-phase1-acceptance.md`. Real `AtlasChunkGenerator` subclass + remapJar fix + in-game cps bench vs C2ME deferred to Phase 2 (requires live MC testing infrastructure).

**Target:** Minecraft 1.21.4 (verified Fabric Loom 1.9.2 integration), Fabric loader 0.16.10, JDK 25. Original spec mentioned MC 26.1 — when that snapshot stabilises with Fabric mappings, atlas-mc will be retargeted in one line.

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
