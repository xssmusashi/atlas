package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.Interpreter;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Real arithmetic tree (15 ops). Interpreter pays virtual dispatch + record field
 * access on every node; JIT inlines everything to a flat sequence of DADD/DMUL/DCMP.
 * <p>
 * Tree:
 * <pre>
 *   clamp(
 *     ((x*0.1) + |y - 64| - z) * 2.0
 *       + max(x*y*0.001, abs(z) - 50)
 *       - min(y, 100),
 *     -1000, 1000
 *   )
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class ArithmeticTreeBench {

    private CompiledSampler interpreter;
    private CompiledSampler jit;

    @Setup
    public void setup() {
        DfcNode tree = buildComplexTree();
        interpreter = new Interpreter(tree);
        jit = JitCompiler.compile(tree);
    }

    private static DfcNode buildComplexTree() {
        // ((x * 0.1) + |y - 64| - z) * 2.0
        DfcNode part1 = new DfcNode.Mul(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.1)),
                    new DfcNode.Abs(new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64.0)))
                ),
                new DfcNode.ZPos()
            ),
            new DfcNode.Constant(2.0)
        );
        // max(x*y*0.001, |z| - 50)
        DfcNode part2 = new DfcNode.Max(
            new DfcNode.Mul(
                new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.YPos()),
                new DfcNode.Constant(0.001)
            ),
            new DfcNode.Sub(new DfcNode.Abs(new DfcNode.ZPos()), new DfcNode.Constant(50.0))
        );
        // min(y, 100)
        DfcNode part3 = new DfcNode.Min(new DfcNode.YPos(), new DfcNode.Constant(100.0));
        // (part1 + part2) - part3
        DfcNode combined = new DfcNode.Sub(new DfcNode.Add(part1, part2), part3);
        // clamp(_, -1000, 1000)
        return new DfcNode.Clamp(combined, -1000.0, 1000.0);
    }

    @Benchmark
    public void interpreter_single(Blackhole bh) {
        bh.consume(interpreter.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void jit_single(Blackhole bh) {
        bh.consume(jit.sample(123, 64, -789, 0L));
    }

    @Benchmark
    public void interpreter_loop_4096(Blackhole bh) {
        for (int i = 0; i < 4096; i++) {
            bh.consume(interpreter.sample(i & 1023, (i >> 4) & 127, -i, 0L));
        }
    }

    @Benchmark
    public void jit_loop_4096(Blackhole bh) {
        for (int i = 0; i < 4096; i++) {
            bh.consume(jit.sample(i & 1023, (i >> 4) & 127, -i, 0L));
        }
    }
}
