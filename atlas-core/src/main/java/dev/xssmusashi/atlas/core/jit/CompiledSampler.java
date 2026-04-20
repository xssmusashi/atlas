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
}
