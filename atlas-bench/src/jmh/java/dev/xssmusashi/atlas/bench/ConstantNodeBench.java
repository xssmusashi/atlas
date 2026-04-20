package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.Interpreter;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 3)
@Fork(1)
public class ConstantNodeBench {

    private CompiledSampler interpreter;
    private CompiledSampler jit;

    @Setup
    public void setup() {
        DfcNode tree = new DfcNode.Constant(42.0);
        interpreter = new Interpreter(tree);
        jit = JitCompiler.compile(tree);
    }

    @Benchmark
    public void interpreter_sample(Blackhole bh) {
        bh.consume(interpreter.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void jit_sample(Blackhole bh) {
        bh.consume(jit.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void interpreter_sample_loop_1024(Blackhole bh) {
        for (int i = 0; i < 1024; i++) {
            bh.consume(interpreter.sample(i, 64, -i, 0L));
        }
    }

    @Benchmark
    public void jit_sample_loop_1024(Blackhole bh) {
        for (int i = 0; i < 1024; i++) {
            bh.consume(jit.sample(i, 64, -i, 0L));
        }
    }
}
