package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InterpreterTest {

    @Test
    void interpretsConstantNode() {
        CompiledSampler sampler = new Interpreter(new DfcNode.Constant(7.0));
        assertThat(sampler.sample(0, 0, 0, 12345L)).isEqualTo(7.0);
        assertThat(sampler.sample(100, 64, -200, 99999L)).isEqualTo(7.0);
    }

    @Test
    void sampleBatch_fillsArrayWithConstant() {
        CompiledSampler sampler = new Interpreter(new DfcNode.Constant(2.5));
        int[] xs = {0, 1, 2, 3};
        int[] ys = {0, 0, 0, 0};
        int[] zs = {0, 0, 0, 0};
        double[] out = new double[4];
        sampler.sampleBatch(xs, ys, zs, 0L, out, 4);
        assertThat(out).containsExactly(2.5, 2.5, 2.5, 2.5);
    }
}
