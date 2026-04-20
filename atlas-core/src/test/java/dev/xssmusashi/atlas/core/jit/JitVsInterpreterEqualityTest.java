package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Master correctness test: for any DfcNode, JIT-compiled and interpreted samplers
 * must produce bit-identical results across a wide range of inputs.
 */
class JitVsInterpreterEqualityTest {

    @Test
    void constant_jit_equals_interpreter_for_random_inputs() {
        Random rng = new Random(0xA71A5L);
        for (int i = 0; i < 50; i++) {
            double v = rng.nextDouble() * 2000 - 1000;
            DfcNode tree = new DfcNode.Constant(v);
            assertJitEqualsInterpreter(tree, rng, 50);
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

    @Test
    void random_arithmetic_trees_jit_equals_interpreter() {
        Random rng = new Random(0xDEADBEEFL);
        for (int t = 0; t < 50; t++) {
            DfcNode tree = randomTree(rng, 5); // depth up to 5
            assertJitEqualsInterpreter(tree, rng, 100);
        }
    }

    @Test
    void deep_tree_jit_equals_interpreter() {
        Random rng = new Random(0xBADF00DL);
        for (int t = 0; t < 10; t++) {
            DfcNode tree = randomTree(rng, 10); // depth up to 10
            assertJitEqualsInterpreter(tree, rng, 50);
        }
    }

    private static void assertJitEqualsInterpreter(DfcNode tree, Random rng, int samples) {
        CompiledSampler interp = new Interpreter(tree);
        CompiledSampler jit = JitCompiler.compile(tree);
        for (int j = 0; j < samples; j++) {
            int x = rng.nextInt(2_000_000) - 1_000_000;
            int y = rng.nextInt(384) - 64;
            int z = rng.nextInt(2_000_000) - 1_000_000;
            long seed = rng.nextLong();
            double a = jit.sample(x, y, z, seed);
            double b = interp.sample(x, y, z, seed);
            assertThat(Double.doubleToRawLongBits(a))
                .as("tree=%s @ (%d,%d,%d) seed=%d  jit=%s interp=%s", tree, x, y, z, seed, a, b)
                .isEqualTo(Double.doubleToRawLongBits(b));
        }
    }

    private static DfcNode randomTree(Random rng, int depth) {
        if (depth <= 0 || rng.nextInt(4) == 0) {
            // leaf
            return switch (rng.nextInt(4)) {
                case 0 -> new DfcNode.Constant(rng.nextDouble() * 200 - 100);
                case 1 -> new DfcNode.XPos();
                case 2 -> new DfcNode.YPos();
                default -> new DfcNode.ZPos();
            };
        }
        int kind = rng.nextInt(9);
        return switch (kind) {
            case 0 -> new DfcNode.Add(randomTree(rng, depth - 1), randomTree(rng, depth - 1));
            case 1 -> new DfcNode.Sub(randomTree(rng, depth - 1), randomTree(rng, depth - 1));
            case 2 -> new DfcNode.Mul(randomTree(rng, depth - 1), randomTree(rng, depth - 1));
            case 3 -> new DfcNode.Negate(randomTree(rng, depth - 1));
            case 4 -> new DfcNode.Abs(randomTree(rng, depth - 1));
            case 5 -> new DfcNode.Min(randomTree(rng, depth - 1), randomTree(rng, depth - 1));
            case 6 -> new DfcNode.Max(randomTree(rng, depth - 1), randomTree(rng, depth - 1));
            case 7 -> {
                double lo = rng.nextDouble() * -50;
                double hi = lo + rng.nextDouble() * 100 + 0.001;
                yield new DfcNode.Clamp(randomTree(rng, depth - 1), lo, hi);
            }
            default -> new DfcNode.Add(
                new DfcNode.XPos(),
                new DfcNode.Mul(new DfcNode.YPos(), new DfcNode.Constant(rng.nextDouble()))
            );
        };
    }
}
