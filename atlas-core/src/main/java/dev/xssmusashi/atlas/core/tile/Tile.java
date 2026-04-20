package dev.xssmusashi.atlas.core.tile;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One unit of Atlas worldgen work: a 128×128-block column spanning full world height.
 * <p>
 * Holds off-heap buffers for noise / biomes / blocks via {@link MemorySegment} (FFM API).
 * Lifetime is bounded by {@link #close()}; backing arena is released on close.
 * <p>
 * <b>Layout for noise field:</b> linear, {@code y * (TILE_BLOCKS * TILE_BLOCKS) + z * TILE_BLOCKS + x}.
 * Y-major because chunk-column iteration sweeps the same XZ across many Y values.
 */
public final class Tile implements AutoCloseable {

    public static final int TILE_BLOCKS = 128;     // 8 chunks * 16 blocks
    public static final int TILE_AREA   = TILE_BLOCKS * TILE_BLOCKS;
    public static final int WORLD_MIN_Y = -64;
    public static final int WORLD_MAX_Y = 320;
    public static final int WORLD_HEIGHT = WORLD_MAX_Y - WORLD_MIN_Y; // 384

    public final TileCoord coord;
    public final long seed;

    private final Arena arena;
    private final AtomicReference<TileState> state = new AtomicReference<>(TileState.NEW);

    private MemorySegment noiseField;   // double[WORLD_HEIGHT * TILE_AREA] off-heap

    public Tile(TileCoord coord, long seed) {
        this.coord = coord;
        this.seed = seed;
        this.arena = Arena.ofShared();
    }

    public TileState state() {
        return state.get();
    }

    /** Atomic state transition. Returns true if successful. */
    public boolean transitionTo(TileState expected, TileState next) {
        return state.compareAndSet(expected, next);
    }

    /** Allocate noise field buffer (lazy — only the stages that need it pay the cost). */
    public MemorySegment ensureNoiseField() {
        MemorySegment ns = noiseField;
        if (ns == null) {
            long bytes = (long) WORLD_HEIGHT * TILE_AREA * Double.BYTES;
            ns = arena.allocate(bytes, Double.BYTES);
            noiseField = ns;
        }
        return ns;
    }

    public MemorySegment noiseField() {
        if (noiseField == null) {
            throw new IllegalStateException("noiseField not allocated; call ensureNoiseField() first");
        }
        return noiseField;
    }

    /** Linear index into the noise field. */
    public static int noiseIndex(int localX, int y, int localZ) {
        int yLocal = y - WORLD_MIN_Y;
        return yLocal * TILE_AREA + localZ * TILE_BLOCKS + localX;
    }

    public static double readNoise(MemorySegment seg, int localX, int y, int localZ) {
        return seg.getAtIndex(ValueLayout.JAVA_DOUBLE, noiseIndex(localX, y, localZ));
    }

    public static void writeNoise(MemorySegment seg, int localX, int y, int localZ, double value) {
        seg.setAtIndex(ValueLayout.JAVA_DOUBLE, noiseIndex(localX, y, localZ), value);
    }

    @Override
    public void close() {
        arena.close();
    }
}
