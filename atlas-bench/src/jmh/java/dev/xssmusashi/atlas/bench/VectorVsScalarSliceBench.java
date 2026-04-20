package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Vector API emitter vs scalar JIT on the same arithmetic-only tree.
 * One slice = 16×16 = 256 sample points.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class VectorVsScalarSliceBench {

    private CompiledSampler scalar;
    private CompiledSampler vector;

    @Setup
    public void setup() {
        // Arithmetic tree: clamp(x*0.001 + |y - 64|*0.05 - z*0.002, -1, 1)
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.001)),
                    new DfcNode.Mul(
                        new DfcNode.Abs(new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64))),
                        new DfcNode.Constant(0.05)
                    )
                ),
                new DfcNode.Mul(new DfcNode.ZPos(), new DfcNode.Constant(0.002))
            ),
            -1.0, 1.0
        );
        scalar = JitCompiler.compile(tree, JitOptions.scalar());
        vector = JitCompiler.compile(tree, JitOptions.vector());
    }

    @Benchmark
    public void scalar_slice256(Blackhole bh) {
        double[] out = new double[256];
        scalar.sampleSlice16(0, 70, 0, 0L, out);
        bh.consume(out);
    }

    @Benchmark
    public void vector_slice256(Blackhole bh) {
        double[] out = new double[256];
        vector.sampleSlice16(0, 70, 0, 0L, out);
        bh.consume(out);
    }
}
