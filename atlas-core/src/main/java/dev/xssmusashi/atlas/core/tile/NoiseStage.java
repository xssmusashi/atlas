package dev.xssmusashi.atlas.core.tile;

import dev.xssmusashi.atlas.core.jit.CompiledSampler;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * Populates a tile's noise field by sampling the JIT-compiled density function
 * at every (x, y, z) cell.
 * <p>
 * Inner loop is ordered z-then-x-then-y NOT y-then-z-then-x: this maximises
 * locality with the field's y-major layout (sequential writes within each Y plane)
 * and keeps the JIT-compiled sampler in the JVM's hot loop body.
 */
public final class NoiseStage {

    private final CompiledSampler sampler;

    public NoiseStage(CompiledSampler sampler) {
        this.sampler = sampler;
    }

    public void run(Tile tile) {
        if (!tile.transitionTo(TileState.NEW, TileState.NOISE)) {
            // Another thread already started/completed this stage.
            return;
        }
        MemorySegment seg = tile.ensureNoiseField();
        int baseX = tile.coord.baseBlockX();
        int baseZ = tile.coord.baseBlockZ();
        long seed = tile.seed;

        // Y-major outer loop matches the field's stride pattern.
        for (int y = Tile.WORLD_MIN_Y; y < Tile.WORLD_MAX_Y; y++) {
            int yPlaneOffset = (y - Tile.WORLD_MIN_Y) * Tile.TILE_AREA;
            for (int dz = 0; dz < Tile.TILE_BLOCKS; dz++) {
                int rowOffset = yPlaneOffset + dz * Tile.TILE_BLOCKS;
                int worldZ = baseZ + dz;
                for (int dx = 0; dx < Tile.TILE_BLOCKS; dx++) {
                    double v = sampler.sample(baseX + dx, y, worldZ, seed);
                    seg.setAtIndex(ValueLayout.JAVA_DOUBLE, rowOffset + dx, v);
                }
            }
        }
    }
}
