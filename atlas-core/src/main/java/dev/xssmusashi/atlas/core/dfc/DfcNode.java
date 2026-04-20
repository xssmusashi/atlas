package dev.xssmusashi.atlas.core.dfc;

/**
 * Density function AST node.
 * <p>
 * Sealed hierarchy — pattern matching over node types in JIT compiler.
 * In Phase 1 only {@link Constant} is implemented. More node types added
 * in subsequent sub-plans (Add, Mul, Clamp, Noise, Spline, ...).
 */
public sealed interface DfcNode {

    record Constant(double value) implements DfcNode {}
}
