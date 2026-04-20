package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VectorAsmEmitterTest {

    @Test
    void supports_returnsTrueForArithmeticOnly() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.1)),
            new DfcNode.Clamp(new DfcNode.YPos(), -1.0, 1.0)
        );
        // Internal API: indirectly tested by AUTO emitter selection, but we also
        // assert the gate behaviour through compile() outcomes below.
        CompiledSampler vec = JitCompiler.compile(tree, JitOptions.vector());
        assertThat(vec).isNotNull();
    }

    @Test
    void supports_returnsFalseForTreesContainingNoise() {
        DfcNode tree = new DfcNode.PerlinNoise(0L, 1.0,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos());
        assertThatThrownBy(() -> JitCompiler.compile(tree, JitOptions.vector()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("VECTOR emitter requested");
    }

    @Test
    void singlePoint_sample_matchesScalarBitExact() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.5)),
            new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.ZPos())
        );
        CompiledSampler scalar = JitCompiler.compile(tree, JitOptions.scalar());
        CompiledSampler vector = JitCompiler.compile(tree, JitOptions.vector());
        for (int i = 0; i < 100; i++) {
            int x = i * 13 - 500;
            int y = (i * 7) % 384 - 64;
            int z = i * 17 - 250;
            assertThat(Double.doubleToRawLongBits(vector.sample(x, y, z, 0L)))
                .as("(%d, %d, %d)", x, y, z)
                .isEqualTo(Double.doubleToRawLongBits(scalar.sample(x, y, z, 0L)));
        }
    }

    @Test
    void sampleSlice16_matchesScalarSampleAtEachCell() {
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.001)),
                new DfcNode.Mul(new DfcNode.YPos(), new DfcNode.Constant(0.01))
            ),
            -2.0, 2.0
        );
        CompiledSampler scalar = JitCompiler.compile(tree, JitOptions.scalar());
        CompiledSampler vector = JitCompiler.compile(tree, JitOptions.vector());

        int baseX = 100, y = 64, baseZ = -200;
        long seed = 42L;
        double[] vecOut = new double[256];
        vector.sampleSlice16(baseX, y, baseZ, seed, vecOut);

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                double expected = scalar.sample(baseX + dx, y, baseZ + dz, seed);
                double actual = vecOut[dz * 16 + dx];
                assertThat(Double.doubleToRawLongBits(actual))
                    .as("cell (%d, %d) world (%d, %d, %d)", dx, dz, baseX + dx, y, baseZ + dz)
                    .isEqualTo(Double.doubleToRawLongBits(expected));
            }
        }
    }

    @Test
    void sampleSlice16_handlesAllSupportedOperators() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Mul(
                new DfcNode.Abs(new DfcNode.Negate(new DfcNode.XPos())),
                new DfcNode.Min(new DfcNode.YPos(), new DfcNode.Constant(50.0))
            ),
            new DfcNode.Max(new DfcNode.ZPos(), new DfcNode.Constant(0.0))
        );
        CompiledSampler scalar = JitCompiler.compile(tree, JitOptions.scalar());
        CompiledSampler vector = JitCompiler.compile(tree, JitOptions.vector());

        double[] vecOut = new double[256];
        vector.sampleSlice16(0, 30, 0, 0L, vecOut);
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                double expected = scalar.sample(dx, 30, dz, 0L);
                double actual = vecOut[dz * 16 + dx];
                assertThat(Double.doubleToRawLongBits(actual))
                    .as("(%d, %d)", dx, dz)
                    .isEqualTo(Double.doubleToRawLongBits(expected));
            }
        }
    }

    @Test
    void auto_picksVectorWhenSupported() {
        DfcNode arith = new DfcNode.Add(new DfcNode.XPos(), new DfcNode.YPos());
        CompiledSampler auto = JitCompiler.compile(arith, JitOptions.DEFAULT);
        // Indirect test: assert the slice path produces valid output.
        double[] out = new double[256];
        auto.sampleSlice16(10, 20, 30, 0L, out);
        // Cell (5, 4) → (15, 20, 34) → 15 + 20 = 35
        assertThat(out[4 * 16 + 5]).isEqualTo(35.0);
    }

    @Test
    void auto_fallsBackToScalarForNoise() {
        DfcNode noise = new DfcNode.PerlinNoise(0L, 0.1,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos());
        // Should not throw — AUTO falls back to scalar.
        CompiledSampler auto = JitCompiler.compile(noise, JitOptions.DEFAULT);
        assertThat(auto.sample(10, 20, 30, 42L))
            .isEqualTo(NoiseRuntime.perlin(42L, 1.0, 2.0, 3.0));
    }
}
