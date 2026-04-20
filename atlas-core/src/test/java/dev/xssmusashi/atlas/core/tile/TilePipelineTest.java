package dev.xssmusashi.atlas.core.tile;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TilePipelineTest {

    private static CompiledSampler simpleSampler() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.001)),
            new DfcNode.Mul(new DfcNode.YPos(), new DfcNode.Constant(0.01))
        );
        return JitCompiler.compile(tree);
    }

    @Test
    void generate_returnsTileInDoneState() throws Exception {
        try (DagScheduler sched = new DagScheduler(2, 8);
             TilePipeline pipe = new TilePipeline(0L, simpleSampler(), sched)) {
            Tile tile = pipe.generate(new TileCoord(0, 0)).get(10, TimeUnit.SECONDS);
            assertThat(tile.state()).isEqualTo(TileState.DONE);
            tile.close();
        }
    }

    @Test
    void generate_dedupesConcurrentRequestsForSameTile() throws Exception {
        try (DagScheduler sched = new DagScheduler(2, 8);
             TilePipeline pipe = new TilePipeline(0L, simpleSampler(), sched)) {
            CompletableFuture<Tile> a = pipe.generate(new TileCoord(0, 0));
            CompletableFuture<Tile> b = pipe.generate(new TileCoord(0, 0));
            // Same instance because of in-flight dedup.
            assertThat(a).isSameAs(b);
            Tile tile = a.get(10, TimeUnit.SECONDS);
            tile.close();
        }
    }

    @Test
    void generate_runsManyTilesInParallel() throws Exception {
        int parallelism = 4;
        int tileCount = 16;
        try (DagScheduler sched = new DagScheduler(parallelism, 32);
             TilePipeline pipe = new TilePipeline(123L, simpleSampler(), sched)) {

            CompletableFuture<?>[] futs = new CompletableFuture<?>[tileCount];
            for (int i = 0; i < tileCount; i++) {
                futs[i] = pipe.generate(new TileCoord(i, 0));
            }
            CompletableFuture.allOf(futs).get(60, TimeUnit.SECONDS);

            for (CompletableFuture<?> f : futs) {
                Tile t = (Tile) f.get();
                assertThat(t.state()).isEqualTo(TileState.DONE);
                t.close();
            }
            assertThat(sched.completedCount()).isEqualTo(tileCount);
        }
    }
}
