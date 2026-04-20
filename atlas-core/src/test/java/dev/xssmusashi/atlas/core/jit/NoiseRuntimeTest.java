package dev.xssmusashi.atlas.core.jit;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NoiseRuntimeTest {

    @Test
    void perlin_returnsZeroAtIntegerLatticePoints() {
        // Perlin noise is exactly 0 at integer-aligned points (gradient . zero-vector).
        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    double v = NoiseRuntime.perlin(42L, x, y, z);
                    assertThat(v).as("perlin(%d, %d, %d)", x, y, z).isCloseTo(0.0, within(1e-12));
                }
            }
        }
    }

    @Test
    void perlin_isDeterministicForSameSeedAndInputs() {
        for (int i = 0; i < 100; i++) {
            double x = i * 0.1;
            double y = i * 0.2;
            double z = i * 0.3;
            double a = NoiseRuntime.perlin(0xCAFEL, x, y, z);
            double b = NoiseRuntime.perlin(0xCAFEL, x, y, z);
            assertThat(a).isEqualTo(b);
        }
    }

    @Test
    void perlin_differsBetweenSeeds() {
        Set<Double> values = new HashSet<>();
        long[] seeds = {0L, 1L, 42L, 0xCAFEL, 0xDEADBEEFL, 1234567890L,
                        -1L, Long.MAX_VALUE, Long.MIN_VALUE,
                        7919L, 104729L, 1000000007L, 0x123456789ABCDEFL,
                        -42L, 0xFEEDFACEL, 0xBADF00DL};
        // (0.5, 0.5, 0.5) is degenerate (centroid of unit cube — fade = 0.5 collapses to
        // an unweighted average of 8 corner gradients, which can coincide across seeds).
        // Use an asymmetric off-lattice point instead.
        for (long seed : seeds) {
            values.add(NoiseRuntime.perlin(seed, 0.3, 0.7, 0.1));
        }
        assertThat(values).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void perlin_outputInExpectedRange() {
        // Improved Perlin's theoretical range is [-1, 1]; in practice slightly narrower.
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 100_000; i++) {
            double x = (i * 0.073) % 64.0;
            double y = (i * 0.041) % 64.0;
            double z = (i * 0.029) % 64.0;
            double v = NoiseRuntime.perlin(0L, x, y, z);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        assertThat(min).isGreaterThanOrEqualTo(-1.05);
        assertThat(max).isLessThanOrEqualTo(1.05);
        // Should actually USE most of the range, not be stuck near 0.
        assertThat(max - min).isGreaterThan(1.0);
    }

    @Test
    void octavePerlin_isDeterministic() {
        for (int i = 0; i < 50; i++) {
            double x = i * 0.13;
            double a = NoiseRuntime.octavePerlin(7L, 4, 0.5, 2.0, x, 0, 0);
            double b = NoiseRuntime.octavePerlin(7L, 4, 0.5, 2.0, x, 0, 0);
            assertThat(a).isEqualTo(b);
        }
    }

    @Test
    void octavePerlin_outputInExpectedRange() {
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 100_000; i++) {
            double v = NoiseRuntime.octavePerlin(0L, 6, 0.5, 2.0,
                (i * 0.073) % 32.0, (i * 0.041) % 32.0, (i * 0.029) % 32.0);
            if (v < min) min = v;
            if (v > max) max = v;
        }
        // Normalized by maxAmp, range should stay within [-1, 1].
        assertThat(min).isGreaterThanOrEqualTo(-1.05);
        assertThat(max).isLessThanOrEqualTo(1.05);
        assertThat(max - min).isGreaterThan(1.0);
    }
}
