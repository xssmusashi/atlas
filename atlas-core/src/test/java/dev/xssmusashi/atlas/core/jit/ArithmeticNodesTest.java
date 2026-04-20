package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies each arithmetic and control node behaves correctly in BOTH
 * the interpreter and the JIT-compiled sampler.
 */
class ArithmeticNodesTest {

    private static double sampleInterp(DfcNode tree, int x, int y, int z) {
        return new Interpreter(tree).sample(x, y, z, 0L);
    }

    private static double sampleJit(DfcNode tree, int x, int y, int z) {
        return JitCompiler.compile(tree).sample(x, y, z, 0L);
    }

    @Test
    void positional_xPosYPosZPos_returnInputCoordinates() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Add(new DfcNode.XPos(), new DfcNode.YPos()),
            new DfcNode.ZPos()
        );
        assertThat(sampleInterp(tree, 1, 2, 3)).isEqualTo(6.0);
        assertThat(sampleJit(tree, 1, 2, 3)).isEqualTo(6.0);
    }

    @Test
    void add_sumsOperands() {
        DfcNode tree = new DfcNode.Add(new DfcNode.Constant(1.5), new DfcNode.Constant(2.5));
        assertThat(sampleInterp(tree, 0, 0, 0)).isEqualTo(4.0);
        assertThat(sampleJit(tree, 0, 0, 0)).isEqualTo(4.0);
    }

    @Test
    void sub_subtractsOperands() {
        DfcNode tree = new DfcNode.Sub(new DfcNode.Constant(10), new DfcNode.Constant(3));
        assertThat(sampleInterp(tree, 0, 0, 0)).isEqualTo(7.0);
        assertThat(sampleJit(tree, 0, 0, 0)).isEqualTo(7.0);
    }

    @Test
    void mul_multipliesOperands() {
        DfcNode tree = new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.1));
        assertThat(sampleInterp(tree, 100, 0, 0)).isEqualTo(10.0);
        assertThat(sampleJit(tree, 100, 0, 0)).isEqualTo(10.0);
    }

    @Test
    void negate_flipsSign() {
        DfcNode tree = new DfcNode.Negate(new DfcNode.YPos());
        assertThat(sampleInterp(tree, 0, 64, 0)).isEqualTo(-64.0);
        assertThat(sampleJit(tree, 0, 64, 0)).isEqualTo(-64.0);
    }

    @Test
    void abs_returnsAbsoluteValue() {
        DfcNode tree = new DfcNode.Abs(new DfcNode.ZPos());
        assertThat(sampleInterp(tree, 0, 0, -42)).isEqualTo(42.0);
        assertThat(sampleJit(tree, 0, 0, -42)).isEqualTo(42.0);
    }

    @Test
    void min_returnsLesser() {
        DfcNode tree = new DfcNode.Min(new DfcNode.XPos(), new DfcNode.YPos());
        assertThat(sampleInterp(tree, 5, 10, 0)).isEqualTo(5.0);
        assertThat(sampleJit(tree, 5, 10, 0)).isEqualTo(5.0);
    }

    @Test
    void max_returnsGreater() {
        DfcNode tree = new DfcNode.Max(new DfcNode.XPos(), new DfcNode.YPos());
        assertThat(sampleInterp(tree, 5, 10, 0)).isEqualTo(10.0);
        assertThat(sampleJit(tree, 5, 10, 0)).isEqualTo(10.0);
    }

    @Test
    void clamp_boundsValueToRange() {
        DfcNode tree = new DfcNode.Clamp(new DfcNode.XPos(), -1.0, 1.0);
        assertThat(sampleInterp(tree, 5, 0, 0)).isEqualTo(1.0);
        assertThat(sampleJit(tree, 5, 0, 0)).isEqualTo(1.0);
        assertThat(sampleInterp(tree, -5, 0, 0)).isEqualTo(-1.0);
        assertThat(sampleJit(tree, -5, 0, 0)).isEqualTo(-1.0);
        assertThat(sampleInterp(tree, 0, 0, 0)).isEqualTo(0.0);
        assertThat(sampleJit(tree, 0, 0, 0)).isEqualTo(0.0);
    }

    @Test
    void complexTree_correctness() {
        // tree: clamp((x * 0.1) + |y - 64| - z, -100, 100)
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.1)),
                    new DfcNode.Abs(new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64)))
                ),
                new DfcNode.ZPos()
            ),
            -100.0, 100.0
        );

        // (10 * 0.1) + |70 - 64| - 5 = 1 + 6 - 5 = 2
        assertThat(sampleInterp(tree, 10, 70, 5)).isEqualTo(2.0);
        assertThat(sampleJit(tree, 10, 70, 5)).isEqualTo(2.0);

        // saturates to 100
        assertThat(sampleInterp(tree, 10000, 0, 0)).isEqualTo(100.0);
        assertThat(sampleJit(tree, 10000, 0, 0)).isEqualTo(100.0);
    }
}
