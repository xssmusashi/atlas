# Atlas — Design Spec

**Дата:** 2026-04-20
**Статус:** Draft for review (Phase 1)
**Автор:** xssmusashi + Claude (collaborative brainstorming)

---

## TL;DR

**Atlas** — Fabric mod для Minecraft 26.1, который генерирует мир в **5–10 раз быстрее C2ME** и **30–50 раз быстрее ванилы** за счёт JIT-компиляции DensityFunction в SIMD-байткод (Vector API), tile-based pipeline (8×8 чанков) и параллельного DAG-планировщика. Цель Phase 1 — **≥1500 устойчивых cps** на потребительском 16-core CPU, без compatibility с vanilla seed (новый формат мира).

---

## 0. Идентичность проекта

| Параметр | Значение |
|---|---|
| Название | **Atlas** |
| GitHub repo | `xssmusashi/atlas` |
| Локальный путь | `D:/atlas` |
| Java package | `dev.xssmusashi.atlas` |
| Лицензия | **Apache-2.0** |
| JDK | **JDK 25** |
| Minecraft target | **26.1** |
| Loader target | **Fabric** (server + client) |
| Modrinth slug | `atlas-worldgen` |

### Обоснование ключевых выборов

- **Apache-2.0** vs MIT: явный patent grant защищает от патентных троллей при работе с агрессивными SIMD/JIT-техниками. vs GPL-3: позволяет встраивать Atlas в проприетарные сервисы (hosted pre-generators), оставляя контроль через trademark.
- **JDK 25**: уже установлен у автора, содержит стабильный 9-й preview Vector API, FFM (`MemorySegment`) для off-heap.
- **MC 26.1 single-target**: упрощает Phase 1, snapshot стабилизировался в `c2me-fabric` ветке `dev/26.1.2`.

---

## 1. Архитектурные решения (зафиксированные)

| # | Решение | Выбор | Почему |
|---|---|---|---|
| 1 | Bit-exact с vanilla seed | **Новый формат мира (без compat)** | Свобода JIT-оптимизаций, FMA, любая параллелизация |
| 2 | Совместимость с Farsight | **Да, через subscriber API + общий формат** | Синергия слоёв, не конкуренты |
| 3 | Дистрибуция | **Fabric mod, server + client** | Один JAR, integrated server тоже подхватывает |
| 4 | C2ME | **Replace, fail-fast detection** | Полный контроль mixin-стека |
| 5 | Phase 1 scope | **JIT + tile pipeline + Fabric integration** | Боевой бенчмарк на выходе, не лабораторный |
| 6 | JIT-стратегия | **Vector API + ASM** (с fallback ASM-only) | Гарантированный SIMD, pure Java, нет JNI-боли |
| 7 | Структура кодовой базы | **Multi-module: core / mc / bench** | Тестируемость core без MC, чистая граница |

---

## 2. High-level архитектура

```
                       atlas-X.Y.Z.jar
                  (Fabric, MC 26.1, JDK 25)
                            │
       ┌────────────────────┼────────────────────┐
       ▼                    ▼                    ▼
┌──────────────┐   ┌──────────────┐    ┌──────────────┐
│  atlas-core  │◄──┤   atlas-mc   │    │ atlas-bench  │
│  pure Java,  │   │  Fabric glue,│    │  JMH harness │
│  no MC deps  │   │  mixins (×2) │    │  no MC deps  │
└──────────────┘   └──────────────┘    └──────────────┘
```

### Pipeline (горячий путь)

```
seed → DfcLoader → JitCompiler → CompiledSampler
                                       │
                                       ▼
                                 TilePipeline
                                       │
                          ┌────────────┼────────────┐
                          ▼            ▼            ▼
                    NoiseStage    BiomeStage   SurfaceStage
                                       │
                                       ▼
                                  CarverStage
                                       │
                                       ▼
                                 FeatureStage (Phase 1: vanilla wrapped)
                                       │
                                       ▼
                                  Tile FULL
                                       │
                          ┌────────────┼────────────┐
                          ▼            ▼            ▼
                    RegionWriter   ChunkSink   ListenersAPI
                    (.atr+zstd)    (→MC chunk)  (Farsight, etc)
```

### Принципы

1. **`atlas-core` ничего не знает про MC.** Только примитивы и собственные типы. Гарантирует: тестируемость в JMH без поднятия сервера, переиспользуемость в CLI/Farsight.
2. **`atlas-mc` — тонкий glue.** Регистрирует `AtlasChunkGenerator`, конвертит Tile→ProtoChunk, держит mixins.
3. **JIT детерминирован.** Один DFC tree + версия компилятора = бит-в-бит идентичный байткод (для disk cache и отладки).
4. **Tile = unit of work, не chunk.** 8×8 чанков (128×128×384). Чанк-границы исчезают на уровне ядра.
5. **C2ME-detection = fail-fast.** При обнаружении C2ME сервер не стартует, явная ошибка.
6. **Subscriber API публичный.** Farsight / DH / любой LOD-мод подписывается без mixin'ов.

---

## 3. `atlas-core` — модули

```
atlas-core/src/main/java/dev/xssmusashi/atlas/core/
├── dfc/        — DensityFunction parser + AST (sealed interface DfcNode + records)
├── jit/        — JIT compiler (ScalarAsmEmitter + VectorAsmEmitter, JitCache)
├── tile/       — TilePipeline, Tile state machine, off-heap fields
├── biome/      — k-d tree biome lookup, batched per tile
├── region/     — Свой .atr формат, zstd dictionary, mmap reads
└── pool/       — DAG scheduler, work-stealing, backpressure
```

### Матрица зависимостей (без циклов)

```
        dfc  jit  tile biome region pool
dfc      -    .    .    .    .      .
jit      ✓    -    .    .    .      .
tile     .    ✓    -    ✓    .      ✓
biome    .    .    .    -    .      .
region   .    .    .    .    -      .
pool     .    .    .    .    .      -
```

### Ключевые классы и их API

#### `dfc/` — DfcLoader, DfcTree, DfcNode
```java
public sealed interface DfcNode {
    record Constant(double value) implements DfcNode {}
    record Add(DfcNode l, DfcNode r) implements DfcNode {}
    record Mul(DfcNode l, DfcNode r) implements DfcNode {}
    record Clamp(DfcNode in, double min, double max) implements DfcNode {}
    record Noise(NoiseParams p, DfcNode x, DfcNode y, DfcNode z) implements DfcNode {}
    record Spline(SplineDef s, DfcNode in) implements DfcNode {}
    record Cache2d(DfcNode wrapped) implements DfcNode {}
    // ~25-30 типов
}
```
Detect DAG (one node referenced N times) → JIT эмитит один раз, переиспользует.

#### `jit/` — JitCompiler, CompiledSampler
```java
public interface CompiledSampler {
    double sample(int x, int y, int z, long seed);
    void sampleBatch(int[] xs, int[] ys, int[] zs, long seed, double[] out, int len);
    void sampleTile(int tileX, int tileZ, long seed, double[] out); // hot path
}
```
- Загрузка через `MethodHandles.Lookup.defineHiddenClass` (class unloading)
- Disk cache: `world/atlas/jit-cache/<sha256>.class`, hash от (tree, emitter version, JDK version)
- Два эмиттера за одним интерфейсом, runtime-выбор после микробенчмарка

#### `tile/` — TilePipeline, Tile
```java
public final class Tile {
    public final TileCoord coord;
    public final TileState state; // NEW | NOISE | BIOMES | SURFACE | CARVED | FEATURED | LIT | FULL
    public NoiseField noise();    // 128×384×128 doubles, off-heap MemorySegment
    public BiomeField biomes();   // 32×32 ints (4×4 ratio как vanilla)
    public BlockField blocks();
}
```
- Off-heap через `MemorySegment` (FFM API, JDK 22+)
- NoiseField column-major (y→z→x) — heightmap-friendly
- Tile state machine с precondition checks

#### `biome/` — BiomeMap (k-d tree)
- 6D climate (temp, humidity, continentalness, erosion, depth, weirdness)
- Batched lookup по тайлу: 32×32 = 1024 точки одной операцией
- 3-5× быстрее vanilla MultiNoise binary search

#### `region/` — AtlasRegionFile (.atr)
- 16×16 tiles per region (= 128×128 chunks)
- Zstd dictionary, обучен после первых 1000 тайлов (плотнее на 30-50%)
- mmap для read через MemorySegment, write-behind buffer
- Атомарная запись через `tile_X.tmp` → fsync → rename

#### `pool/` — DagScheduler
- ForkJoinPool с `parallelism = nCpus - 1`
- Tile-task graph: each task declares dependsOn(), scheduler resolves
- Bounded backpressure: `max_inflight_tiles` (default 64)
- Priority: closer to player = higher (для live-генерации)

---

## 4. `atlas-mc` — Minecraft integration

```
atlas-mc/src/main/java/dev/xssmusashi/atlas/mc/
├── AtlasMod.java                  — ModInitializer
├── AtlasConfig.java               — config/atlas.json5 loader
├── compat/
│   ├── C2MEDetector.java          — fail-fast при C2ME
│   └── KnownIncompats.java        — warnings list
├── gen/
│   ├── AtlasChunkGenerator.java   — ChunkGenerator subclass
│   ├── AtlasBiomeSource.java      — BiomeSource subclass
│   ├── ChunkBridge.java           — Tile → ProtoChunk
│   └── DfcBridge.java             — MC DensityFunction registry → DfcTree
├── reg/
│   ├── GeneratorRegistry.java     — register 'atlas:default'
│   └── PresetRegistry.java        — world preset 'atlas:overworld'
├── lifecycle/
│   ├── ServerHooks.java
│   └── PipelineLifecycle.java     — TilePipeline per-world
└── mixin/
    ├── ChunkLoadingManagerMixin.java   — bypass main thread
    └── ChunkSerializerMixin.java       — read from .atr if present
```

### Mixin философия: минимум

Только две точки, обе обоснованы:

1. **`ChunkLoadingManagerMixin`** — bypass vanilla `getChunkFutureMainThread` для AtlasChunkGenerator, прямая дорога в DAG scheduler. Vanilla bouncing губит параллелизм.
2. **`ChunkSerializerMixin`** — если чанк уже в `.atr`, читать оттуда, не трогая vanilla NBT loader.

### Конфиг — `config/atlas.json5`

```json5
{
  "jit_emitter": "auto",          // auto | scalar | vector
  "jit_disk_cache": true,
  "jit_dump_classes": false,      // debug
  "parallelism": 0,               // 0 = nCpus - 1
  "max_inflight_tiles": 64,
  "use_atr_format": true,         // false = fallback на vanilla .mca
  "zstd_level": 3,
  "zstd_dict_training": true,
  "max_tile_cache_mb": 1024,
  "log_cps_interval_seconds": 30
}
```

### Phase 1 scope ограничения

- **Только overworld** (nether/end через vanilla generator → Phase 2)
- **Без своего lighting** (vanilla / Starlight совместимы → Phase 2)
- **Features через vanilla wrapped** (per-chunk, не tile-batched → Phase 2)

---

## 5. Data flow & lifecycle

### Cold start (первый запуск, JIT cache пуст)

```
Server start → AtlasMod.onInitialize() → C2MEDetector (no C2ME)
   → World load → AtlasChunkGenerator constructor (lazy pipeline)
   → First chunk request → ensurePipelineInit():
       DfcBridge.fromMcOverworld() → DfcTree (200 nodes)
       JitCompiler.compile() → CACHE MISS
         VectorAsmEmitter.emit(tree) → bytecode (~30ms)
         Lookup.defineHiddenClass() → Class<?> (~2ms)
         JitCache.store(hash, bytecode) → disk
       TilePipeline.create() → ready
   Cold start: ~200ms total на первый чанк
```

### Hot start (со 2-го запуска)

```
First chunk → ensurePipelineInit():
    JitCompiler.compile() → CACHE HIT (~1ms)
    Lookup.defineHiddenClass() → Class<?>
Cold start: ~10ms total
```

### Hot path — tile generation (1500+ раз/сек)

```
ChunkRequest(chunkPos)
   → AtlasChunkGenerator.populateNoise(chunk)
   → TileCoord tc = TileCoord.fromChunk(chunkPos)  // 8×8 grouping
   → tileCache.computeIfAbsent(tc, () -> scheduler.schedule(new TileTask(tc) {
       NoiseStage   ~5ms   ◄── CompiledSampler.sampleTile() — JIT magic
       BiomeStage   ~2ms   ◄── BiomeMap.lookupTile()
       SurfaceStage ~3ms
       CarverStage  ~5ms   (Phase 1: vanilla wrapped)
       FeatureStage ~10ms  (Phase 1: vanilla wrapped)
       publishToListeners(tile)  // Farsight hook
   }))
   → ChunkBridge.copyTileChunkToMc(tile, chunkPos, dst)
   → return chunk
```

**Ожидаемое время на тайл:** ~25ms → 64 chunks/tile → **~2500 cps пиковых, ~1500 cps устойчивых** на 16-core.

### Persistence

MC выгружает по чанкам — Atlas ждёт, пока выгружены все 64 чанка тайлы, пишет целиком:
```
ChunkSerializer.serialize → AtlasRegionStorage.writeTile(tc):
    ZstdWriter.compress(tile.serialize())
    AsyncWriter.queue(file, offset, bytes) → write-behind
```
Если краш до завершения тайлы — `.tmp` файл игнорируется, тайл перегенерится (детерминизм).

### Subscription API (Farsight)

```java
public interface AtlasGenerationListener {
    void onNoiseDone(Tile tile);    // sync, must be fast
    void onBiomesDone(Tile tile);
    void onSurfaceDone(Tile tile);
    void onTileFull(Tile tile);
}
Atlas.api().registerListener(new ...);
```
Контракт: listeners synchronous внутри worker thread. Документируем — должны быть быстрыми или async-delegate в свой пул.

---

## 6. Error handling & edge cases

| Сценарий | Реакция |
|---|---|
| Atlas + C2ME в `mods/` | **Fail-fast при загрузке**, явная ошибка в лог |
| JIT compilation failure | Fallback на интерпретатор, dump tree в `world/atlas/crash/` |
| Server crash во время записи тайла | `.tmp` файл игнорируется → перегенерация (детерминизм) |
| OOM от tile in-flight | Bounded `max_inflight_tiles=64` blocks 65-й request |
| MC version mismatch (например 26.2) | Graceful fallback на vanilla generator + warning |
| Datapack с custom DFC nodes | Fallback на интерпретатор для unknown nodes |
| Atlas удалён, мир остался | Vanilla MC выдаст unknown generator (стандартное поведение) |
| JIT cache битый (другой JDK) | Hash включает JDK version → recompile |
| Lithium / Sodium / Iris / Starlight | Совместимы (no overlap) |
| JIT-сгенерированный код некорректен | Debug mode dumps `.class`, `verify_against_interpreter` опция |

### Философия

- **Совместимость** — fail-fast
- **Корректность** — fallback + лог + дамп
- **Ресурсы** — backpressure, не падаем
- **Краш** — атомарные writes → ничего не теряется
- **Версии** — graceful degradation на vanilla

**Ничто не убивает мир игроков.**

---

## 7. Тестирование

### Уровень 1: unit-тесты `atlas-core` (без MC, секунды)

| Тест | Цель |
|---|---|
| Парсер DFC | vanilla overworld JSON → ожидаемые узлы |
| JIT correctness | 1000 random points, interpreter vs JIT, 1-ULP допуск |
| JIT детерминизм | один tree → одинаковый байткод |
| Биом-кэш | 10000 climate vectors, k-d tree vs vanilla binary search |
| Region round-trip | write → read → byte-equal |
| DAG scheduler | 100 задач с зависимостями → правильный порядок |

**Цель покрытия:** 90% `atlas-core`, 1500+ тестов.

### Уровень 2: JMH benchmarks `atlas-bench` (acceptance)

| Бенчмарк | Acceptance |
|---|---|
| `NoiseSamplerBench` | JIT Vector ≥ 15× к interpreter |
| `TileNoiseBench` | ≥ 20× |
| `BiomeLookupBench` | ≥ 3× |
| `RegionWriteBench` | ≥ 2× throughput, ≤ 0.7× размер |
| `EndToEndCpsBench` | ≥ 1500 cps |

Гоняются ежедневно в CI, регрессы → тревога.

### Уровень 3: integration tests `atlas-mc` (headless MC)

- World creation
- 1000 chunks generation, валидация биомов
- Save/load round-trip (block identity)
- C2ME detection
- Memory leak (100k чанков → heap stable)

Гоняются на каждый PR.

### Уровень 4: ручной boss-fight stand (раз в неделю)

MC сервер → Chunky pre-gen радиус 5000 → cps, RAM, CPU, сравнение с прошлой неделей и C2ME baseline.

### TDD дисциплина

Согласно `superpowers:test-driven-development`:
- Каждая фича — тест ДО имплементации
- JIT-эмиттер: тест «interpreter vs JIT для tree X» → FAIL → implement → PASS

### CI: GitHub Actions

```yaml
unit:    JDK 25 → ./gradlew :atlas-core:test       # ~1 min
bench:   JDK 25 → ./gradlew :atlas-bench:jmh -i 1  # ~5 min
integ:   JDK 25 → ./gradlew :atlas-mc:integTest    # ~10 min, only on PR
build:   ./gradlew build                            # ~3 min
```

---

## 8. Phase 1 deliverables

### Артефакты

1. `atlas-X.Y.Z.jar` — Fabric mod для MC 26.1
2. GitHub repo `xssmusashi/atlas`, Apache-2.0
3. README + базовый FAQ
4. Wiki: «Why Atlas is incompatible with C2ME»
5. Бенчмарк-репорт: vs vanilla, vs C2ME на одинаковом железе
6. Modrinth release (если acceptance прошёл)

### Acceptance criteria

Phase 1 объявляется успешной если выполнены ВСЕ:

| Метрика | Цель |
|---|---|
| JIT correctness | bit-exact с интерпретатором (1-ULP для FMA), 1M random points |
| JIT noise speedup | ≥ 15× vs interpreter, single-thread |
| Tile noise speedup | ≥ 20× vs baseline |
| End-to-end CPS | ≥ 1500 cps overworld, single player, 16-core CPU |
| **vs C2ME** | **≥ 5× cps на одинаковом железе** |
| Stability | 100k chunks без OOM/crash |
| Cold start | < 1 сек server start → first chunk (с cache) |
| Region size | ≤ 0.8× vanilla `.mca` |

Если **любой** не достигнут — Phase 1 не закрыта, либо доделка, либо pivot.

### Тайминг

5–6 недель в режиме фокусной работы по 3-4 часа в день.

---

## 9. Roadmap (после Phase 1, для контекста)

| Phase | Срок | Содержание | Цель |
|---|---|---|---|
| 2 | ~2 мес | Nether/End, Atlas lighting, tile-batched features | ≥ 4000 cps overall |
| 3 | ~1 мес | DH + Farsight глубокая интеграция, live LOD при генерации | — |
| 4 | ~1 мес | Standalone CLI pre-generator (без MC сервера) | дата-центры, mass pre-gen |

---

## 10. Известные риски

1. **Vector API всё ещё incubator** — изменения API между JDK release. Mitigation: lock на JDK 25, bump осознанно.
2. **MC 26.1 DFC structure** может измениться в патчах — DfcBridge придётся обновлять. Mitigation: graceful fallback на vanilla.
3. **JIT может не дать 15× прирост** на нерегулярных деревьях. Mitigation: fallback на ScalarAsmEmitter, в худшем случае на интерпретатор. Если общий cps < 1500 — Phase 1 fail, pivot.
4. **C2ME авторы (ishland) могут реализовать что-то похожее** — у них уже native math. Mitigation: фокусируемся на полном tile-pipeline (что они не делают), наш JIT шире.
5. **Apache vs GPL** для borrowed C2ME ideas (worldgen-vanilla bug fixes) — переписываем своими словами, не копируем строки.
6. **Modrinth audience может не принять** «новый формат мира». Mitigation: явное позиционирование как «новый pre-gen для крупных серверов», не замена ванилы для casual.

---

## 11. Не-цели Phase 1 (явно)

- Совместимость с существующими vanilla-мирами
- Поддержка Forge / NeoForge / Paper / Folia
- Своё lighting / structures / decorators
- Multi-version MC support
- vanilla-conversion-tool (миграция)
- Bedrock Edition

Эти цели либо в Phase 2-4, либо вне scope проекта вообще.

---

## Финальный statement

**Atlas Phase 1 = тысячи чанков в секунду на новом overworld-мире, через JIT'нутый workflow + tile pipeline + параллельный scheduler.**

**5–10× к C2ME, 30–50× к ванилле**, архитектура готова масштабироваться к Phase 2-4.
