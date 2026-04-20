package dev.xssmusashi.atlas.core.tile;

import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.pool.DagScheduler;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-level worldgen pipeline. Coordinates stages on a per-tile basis,
 * dedupes concurrent requests for the same tile, and runs all work on
 * the supplied {@link DagScheduler}.
 * <p>
 * Phase 1 contains only the {@link NoiseStage}; sub-plan 5 adds biomes/surface,
 * sub-plan 6 adds features/light.
 */
public final class TilePipeline implements AutoCloseable {

    private final long seed;
    private final NoiseStage noiseStage;
    private final DagScheduler scheduler;
    private final Map<TileCoord, CompletableFuture<Tile>> inflight = new ConcurrentHashMap<>();

    public TilePipeline(long seed, CompiledSampler sampler, DagScheduler scheduler) {
        this.seed = seed;
        this.noiseStage = new NoiseStage(sampler);
        this.scheduler = scheduler;
    }

    /**
     * Get (or generate) the tile at {@code coord}. Concurrent requests for the same
     * tile share a single in-flight future.
     */
    public CompletableFuture<Tile> generate(TileCoord coord) {
        return inflight.computeIfAbsent(coord, c -> scheduler.submit(() -> {
            Tile tile = new Tile(c, seed);
            noiseStage.run(tile);
            tile.transitionTo(TileState.NOISE, TileState.DONE);
            return tile;
        }));
    }

    /** Forget a tile from the in-flight cache (caller closes the Tile). */
    public void evict(TileCoord coord) {
        inflight.remove(coord);
    }

    public int inflightSize() {
        return inflight.size();
    }

    @Override
    public void close() {
        // Caller is responsible for closing tiles; pipeline does not own them.
        inflight.clear();
    }
}
