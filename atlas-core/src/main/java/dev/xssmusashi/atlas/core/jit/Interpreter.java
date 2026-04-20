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
        };
    }
}
