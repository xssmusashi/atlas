package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master correctness test: for any DfcNode, JIT-compiled and interpreted samplers
 * must produce bit-identical results across a wide range of inputs.
 * <p>
 * This test grows with each new node type. Phase 1: Constant only.
 */
class JitVsInterpreterEqualityTest {

    @Test
    void constant_jit_equals_interpreter_for_random_inputs() {
        Random rng = new Random(0xA71A5L);
        for (int i = 0; i < 100; i++) {
            double v = rng.nextDouble() * 2000 - 1000;
            DfcNode tree = new DfcNode.Constant(v);
            CompiledSampler interp = new Interpreter(tree);
            CompiledSampler jit = JitCompiler.compile(tree);
            for (int j = 0; j < 100; j++) {
                int x = rng.nextInt();
                int y = rng.nextInt(384);
                int z = rng.nextInt();
                long seed = rng.nextLong();
                assertThat(jit.sample(x, y, z, seed))
                    .as("constant=%s @ (%d,%d,%d) seed=%d", v, x, y, z, seed)
                    .isEqualTo(interp.sample(x, y, z, seed));
            }
        }
    }

    @Test
    void edge_values_negative_zero_max_min_nan_infinity() {
        double[] edges = {
            0.0, -0.0, 1.0, -1.0,
            Double.MIN_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY
        };
        for (double v : edges) {
            DfcNode tree = new DfcNode.Constant(v);
            CompiledSampler interp = new Interpreter(tree);
            CompiledSampler jit = JitCompiler.compile(tree);
            assertThat(Double.doubleToRawLongBits(jit.sample(0, 0, 0, 0L)))
                .as("edge value %s", v)
                .isEqualTo(Double.doubleToRawLongBits(interp.sample(0, 0, 0, 0L)));
        }
    }
}
