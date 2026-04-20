package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.Interpreter;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Realistic worldgen-shaped tree: continentalness (8-octave Perlin),
 * scaled and combined with Y gradient and clamped. This is essentially
 * what a vanilla overworld terrain density function looks like.
 * <p>
 * On a chunk's worth of points (16×16×384 = 98 304 samples per chunk),
 * even a small per-sample speedup compounds dramatically.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class WorldgenNoiseBench {

    private CompiledSampler interpreter;
    private CompiledSampler jit;

    @Setup
    public void setup() {
        DfcNode tree = buildWorldgenShapedTree();
        interpreter = new Interpreter(tree);
        jit = JitCompiler.compile(tree);
    }

    private static DfcNode buildWorldgenShapedTree() {
        // continentalness = 8-octave Perlin at frequency 0.005 over (x, 0, z)
        DfcNode continentalness = new DfcNode.OctavePerlin(
            0L, 8, 0.5, 2.0, 0.005,
            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()
        );
        // erosion = 6-octave Perlin at frequency 0.012 over (x, 0, z)
        DfcNode erosion = new DfcNode.OctavePerlin(
            12345L, 6, 0.5, 2.0, 0.012,
            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()
        );
        // 3D detail = 4-octave Perlin at freq 0.02 over (x,y,z)
        DfcNode detail3d = new DfcNode.OctavePerlin(
            999L, 4, 0.5, 2.0, 0.02,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        // density = clamp(continentalness * 1.5 + erosion * 0.5 + detail3d * 0.3 - (y - 64) / 96, -1, 1)
        DfcNode tree = new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Add(
                        new DfcNode.Mul(continentalness, new DfcNode.Constant(1.5)),
                        new DfcNode.Mul(erosion, new DfcNode.Constant(0.5))
                    ),
                    new DfcNode.Mul(detail3d, new DfcNode.Constant(0.3))
                ),
                new DfcNode.Mul(
                    new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64)),
                    new DfcNode.Constant(1.0 / 96.0)
                )
            ),
            -1.0, 1.0
        );
        return tree;
    }

    @Benchmark
    public void interpreter_single(Blackhole bh) {
        bh.consume(interpreter.sample(123, 64, -789, 42L));
    }

    @Benchmark
    public void jit_single(Blackhole bh) {
        bh.consume(jit.sample(123, 64, -789, 42L));
    }

    /** Simulates one Y-column of a chunk (16×384 = 6144 points). */
    @Benchmark
    public void interpreter_chunkColumn_6144(Blackhole bh) {
        for (int dx = 0; dx < 16; dx++) {
            for (int y = -64; y < 320; y++) {
                bh.consume(interpreter.sample(dx, y, 0, 42L));
            }
        }
    }

    @Benchmark
    public void jit_chunkColumn_6144(Blackhole bh) {
        for (int dx = 0; dx < 16; dx++) {
            for (int y = -64; y < 320; y++) {
                bh.consume(jit.sample(dx, y, 0, 42L));
            }
        }
    }
}
