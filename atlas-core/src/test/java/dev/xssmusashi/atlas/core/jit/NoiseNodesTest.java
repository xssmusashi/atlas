package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies noise nodes produce identical results in the interpreter and JIT,
 * and that they actually call NoiseRuntime correctly.
 */
class NoiseNodesTest {

    private static double interp(DfcNode tree, int x, int y, int z, long seed) {
        return new Interpreter(tree).sample(x, y, z, seed);
    }

    private static double jit(DfcNode tree, int x, int y, int z, long seed) {
        return JitCompiler.compile(tree).sample(x, y, z, seed);
    }

    @Test
    void perlin_matchesNoiseRuntime() {
        DfcNode tree = new DfcNode.PerlinNoise(
            0L, 0.01, new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        for (int i = 0; i < 50; i++) {
            int x = i * 17;
            int y = (i * 31) % 256;
            int z = i * 23;
            double expected = NoiseRuntime.perlin(123L, x * 0.01, y * 0.01, z * 0.01);
            assertThat(interp(tree, x, y, z, 123L)).isEqualTo(expected);
            assertThat(jit(tree, x, y, z, 123L)).isEqualTo(expected);
        }
    }

    @Test
    void perlin_seedOffsetIsApplied() {
        // Frequency = 0.37 → off-lattice points; expected values use the same
        // multiplications the JIT performs (avoids IEEE 754 mismatch).
        double freq = 0.37;
        DfcNode treeBase = new DfcNode.PerlinNoise(
            0L, freq, new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        DfcNode treeOffset = new DfcNode.PerlinNoise(
            500L, freq, new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        int x = 1, y = 2, z = 3;
        long seed = 100L;
        double base   = jit(treeBase,   x, y, z, seed);
        double offset = jit(treeOffset, x, y, z, seed);
        assertThat(base).isEqualTo(NoiseRuntime.perlin(seed,         x * freq, y * freq, z * freq));
        assertThat(offset).isEqualTo(NoiseRuntime.perlin(seed + 500, x * freq, y * freq, z * freq));
        assertThat(base).isNotEqualTo(offset);
    }

    @Test
    void octavePerlin_jitMatchesInterpreter() {
        DfcNode tree = new DfcNode.OctavePerlin(
            0L, 4, 0.5, 2.0, 0.05,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        for (int i = 0; i < 50; i++) {
            int x = i * 7 - 100;
            int y = i * 3 + 50;
            int z = i * 5 - 75;
            assertThat(jit(tree, x, y, z, 999L))
                .isEqualTo(interp(tree, x, y, z, 999L));
        }
    }

    @Test
    void worldgenShapedTree_jitMatchesInterpreter() {
        // Simulates a real worldgen shape: multi-octave Perlin scaled, clamped, mixed.
        // density(x,y,z) = clamp(continentalness * 2 - y/64.0, -1, 1)
        // continentalness = octave_perlin(8 octaves, freq=0.005)
        DfcNode continentalness = new DfcNode.OctavePerlin(
            0L, 8, 0.5, 2.0, 0.005,
            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()
        );
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Mul(continentalness, new DfcNode.Constant(2.0)),
                new DfcNode.Mul(new DfcNode.YPos(), new DfcNode.Constant(1.0 / 64.0))
            ),
            -1.0, 1.0
        );
        for (int i = 0; i < 30; i++) {
            int x = i * 41 - 500;
            int y = (i * 19) % 384;
            int z = i * 53 - 250;
            assertThat(Double.doubleToRawLongBits(jit(tree, x, y, z, 0xDEADL)))
                .isEqualTo(Double.doubleToRawLongBits(interp(tree, x, y, z, 0xDEADL)));
        }
    }
}
