package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarAsmEmitterTest {

    @Test
    void compilesConstantNodeToBytecode() {
        DfcNode tree = new DfcNode.Constant(99.0);
        CompiledSampler jit = JitCompiler.compile(tree);
        assertThat(jit.sample(0, 0, 0, 0L)).isEqualTo(99.0);
        assertThat(jit.sample(123, 456, 789, 42L)).isEqualTo(99.0);
    }

    @Test
    void compiledSamplerImplementsInterface() {
        CompiledSampler jit = JitCompiler.compile(new DfcNode.Constant(0.0));
        assertThat(jit).isInstanceOf(CompiledSampler.class);
    }

    @Test
    void multipleCompilations_yieldIndependentSamplers() {
        CompiledSampler a = JitCompiler.compile(new DfcNode.Constant(1.0));
        CompiledSampler b = JitCompiler.compile(new DfcNode.Constant(2.0));
        assertThat(a.sample(0, 0, 0, 0L)).isEqualTo(1.0);
        assertThat(b.sample(0, 0, 0, 0L)).isEqualTo(2.0);
    }
}
