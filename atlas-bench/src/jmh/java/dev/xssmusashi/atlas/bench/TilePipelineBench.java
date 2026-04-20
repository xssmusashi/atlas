package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import dev.xssmusashi.atlas.core.tile.TilePipeline;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Headline Phase 1 benchmark: end-to-end tile generation throughput.
 * <p>
 * Each invocation generates {@link #BATCH_TILES} fresh tiles (= BATCH_TILES × 64 chunks)
 * through the full pipeline (JIT-compiled noise stage). Measures wall-clock time
 * for the batch — divide by 64 to get chunks/sec equivalent.
 * <p>
 * Parametrised by parallelism: 1, 2, 4, 8 — shows scheduler scaling.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 3, time = 10)
@Fork(1)
public class TilePipelineBench {

    /** Each tile = 8×8 chunks. 16 tiles = 1024 chunks per invocation. */
    public static final int BATCH_TILES = 16;
    public static final int CHUNKS_PER_TILE = 64;

    @Param({"1", "2", "4", "8"})
    public int parallelism;

    private CompiledSampler sampler;
    private DagScheduler scheduler;
    private TilePipeline pipeline;
    private int batchOffset; // unique tile coords per invocation

    @Setup(Level.Trial)
    public void setupTrial() {
        // Realistic worldgen-shaped tree (same as WorldgenNoiseBench).
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Mul(
                        new DfcNode.OctavePerlin(0L, 8, 0.5, 2.0, 0.005,
                            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()),
                        new DfcNode.Constant(1.5)
                    ),
                    new DfcNode.Mul(
                        new DfcNode.OctavePerlin(12345L, 6, 0.5, 2.0, 0.012,
                            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()),
                        new DfcNode.Constant(0.5)
                    )
                ),
                new DfcNode.Mul(
                    new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64)),
                    new DfcNode.Constant(1.0 / 96.0)
                )
            ),
            -1.0, 1.0
        );
        sampler = JitCompiler.compile(tree);
    }

    @Setup(Level.Iteration)
    public void setupIteration() {
        scheduler = new DagScheduler(parallelism, Math.max(parallelism * 2, 8));
        pipeline = new TilePipeline(0xC0FFEEL, sampler, scheduler);
        batchOffset = 0;
    }

    @TearDown(Level.Iteration)
    public void teardownIteration() {
        pipeline.close();
        scheduler.close();
    }

    @Benchmark
    public void generate_batch(Blackhole bh) throws Exception {
        // Use a fresh band of tile coords each invocation so we never hit the in-flight cache.
        int origin = batchOffset;
        batchOffset += BATCH_TILES * 4; // wide stride to avoid any locality reuse

        @SuppressWarnings("unchecked")
        CompletableFuture<Tile>[] futs = new CompletableFuture[BATCH_TILES];
        for (int i = 0; i < BATCH_TILES; i++) {
            futs[i] = pipeline.generate(new TileCoord(origin + i * 4, 0));
        }
        for (CompletableFuture<Tile> f : futs) {
            Tile t = f.get();
            bh.consume(t);
            t.close();
            pipeline.evict(t.coord);
        }
    }
}
