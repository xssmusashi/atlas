package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

/**
 * Tree-walking interpreter for {@link DfcNode}. Baseline for correctness comparison
 * and benchmarking against JIT-compiled samplers.
 */
public final class Interpreter implements CompiledSampler {

    private final DfcNode root;

    public Interpreter(DfcNode root) {
        this.root = root;
    }

    @Override
    public double sample(int x, int y, int z, long seed) {
        return eval(root, x, y, z, seed);
    }

    private static double eval(DfcNode node, int x, int y, int z, long seed) {
        return switch (node) {
            case DfcNode.Constant c -> c.value();
            case DfcNode.XPos ignored -> (double) x;
            case DfcNode.YPos ignored -> (double) y;
            case DfcNode.ZPos ignored -> (double) z;
            case DfcNode.Add a -> eval(a.left(), x, y, z, seed) + eval(a.right(), x, y, z, seed);
            case DfcNode.Sub s -> eval(s.left(), x, y, z, seed) - eval(s.right(), x, y, z, seed);
            case DfcNode.Mul m -> eval(m.left(), x, y, z, seed) * eval(m.right(), x, y, z, seed);
            case DfcNode.Negate n -> -eval(n.input(), x, y, z, seed);
            case DfcNode.Abs a -> Math.abs(eval(a.input(), x, y, z, seed));
            case DfcNode.Min m -> Math.min(eval(m.left(), x, y, z, seed), eval(m.right(), x, y, z, seed));
            case DfcNode.Max m -> Math.max(eval(m.left(), x, y, z, seed), eval(m.right(), x, y, z, seed));
            case DfcNode.Clamp c -> {
                double v = eval(c.input(), x, y, z, seed);
                yield Math.min(Math.max(v, c.min()), c.max());
            }
            case DfcNode.PerlinNoise n -> NoiseRuntime.perlin(
                seed + n.seedOffset(),
                eval(n.x(), x, y, z, seed) * n.frequency(),
                eval(n.y(), x, y, z, seed) * n.frequency(),
                eval(n.z(), x, y, z, seed) * n.frequency()
            );
            case DfcNode.OctavePerlin n -> NoiseRuntime.octavePerlin(
                seed + n.seedOffset(),
                n.octaves(), n.persistence(), n.lacunarity(),
                eval(n.x(), x, y, z, seed) * n.frequency(),
                eval(n.y(), x, y, z, seed) * n.frequency(),
                eval(n.z(), x, y, z, seed) * n.frequency()
            );
        };
    }
}
