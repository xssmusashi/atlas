package dev.xssmusashi.atlas.core.jit;

/**
 * Density function sampler — either an interpreter or a JIT-compiled implementation.
 * <p>
 * Implementations must be thread-safe (no mutable state) and deterministic
 * (same inputs → same output).
 */
public interface CompiledSampler {

    /** Sample at a single point. */
    double sample(int x, int y, int z, long seed);

    /** Sample a batch of points. {@code out} length must be ≥ {@code len}. */
    default void sampleBatch(int[] xs, int[] ys, int[] zs, long seed, double[] out, int len) {
        for (int i = 0; i < len; i++) {
            out[i] = sample(xs[i], ys[i], zs[i], seed);
        }
    }

    /**
     * Sample a 16×16 horizontal slice at fixed Y. Hot path for noise generation
     * over a tile. Output is row-major (z * 16 + x).
     * <p>
     * Default implementation calls {@link #sample(int, int, int, long)} per cell;
     * Vector emitter overrides this with SIMD.
     *
     * @param baseX base X (block coordinate of cell (0,0))
     * @param y     Y coordinate (constant for the slice)
     * @param baseZ base Z (block coordinate of cell (0,0))
     * @param seed  RNG seed
     * @param out   length-256 output array
     */
    default void sampleSlice16(int baseX, int y, int baseZ, long seed, double[] out) {
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                out[dz * 16 + dx] = sample(baseX + dx, y, baseZ + dz, seed);
            }
        }
    }
}
