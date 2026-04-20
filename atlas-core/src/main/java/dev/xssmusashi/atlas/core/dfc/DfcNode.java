package dev.xssmusashi.atlas.core.dfc;

/**
 * Density function AST node.
 * <p>
 * Sealed hierarchy — pattern matching over node types in JIT compiler.
 * <p>
 * Sub-plan 1: {@link Constant}.
 * Sub-plan 2: positional ({@link XPos}, {@link YPos}, {@link ZPos}),
 * arithmetic ({@link Add}, {@link Sub}, {@link Mul}, {@link Negate}, {@link Abs}),
 * control ({@link Min}, {@link Max}, {@link Clamp}).
 */
public sealed interface DfcNode {

    // --- leaves ---
    record Constant(double value) implements DfcNode {}
    record XPos() implements DfcNode {}
    record YPos() implements DfcNode {}
    record ZPos() implements DfcNode {}

    // --- arithmetic ---
    record Add(DfcNode left, DfcNode right) implements DfcNode {}
    record Sub(DfcNode left, DfcNode right) implements DfcNode {}
    record Mul(DfcNode left, DfcNode right) implements DfcNode {}
    record Negate(DfcNode input) implements DfcNode {}
    record Abs(DfcNode input) implements DfcNode {}

    // --- control ---
    record Min(DfcNode left, DfcNode right) implements DfcNode {}
    record Max(DfcNode left, DfcNode right) implements DfcNode {}
    record Clamp(DfcNode input, double min, double max) implements DfcNode {}
}
