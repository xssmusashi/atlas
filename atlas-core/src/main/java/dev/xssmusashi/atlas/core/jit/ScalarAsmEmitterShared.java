package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Scalar bytecode emission shared between {@link ScalarAsmEmitter} and
 * {@link VectorAsmEmitter} (which still emits scalar code for the single-point
 * {@code sample(int, int, int, long)} entry).
 */
final class ScalarAsmEmitterShared implements Opcodes {

    private ScalarAsmEmitterShared() {}

    private static final String NOISE_RUNTIME = Type.getInternalName(NoiseRuntime.class);

    static void emitScalarNode(MethodVisitor mv, DfcNode node,
                               int xSlot, int ySlot, int zSlot, int seedSlot) {
        switch (node) {
            case DfcNode.Constant c -> mv.visitLdcInsn(c.value());

            case DfcNode.XPos ignored -> { mv.visitVarInsn(ILOAD, xSlot); mv.visitInsn(I2D); }
            case DfcNode.YPos ignored -> { mv.visitVarInsn(ILOAD, ySlot); mv.visitInsn(I2D); }
            case DfcNode.ZPos ignored -> { mv.visitVarInsn(ILOAD, zSlot); mv.visitInsn(I2D); }

            case DfcNode.Add a -> {
                emitScalarNode(mv, a.left(), xSlot, ySlot, zSlot, seedSlot);
                emitScalarNode(mv, a.right(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitInsn(DADD);
            }
            case DfcNode.Sub s -> {
                emitScalarNode(mv, s.left(), xSlot, ySlot, zSlot, seedSlot);
                emitScalarNode(mv, s.right(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitInsn(DSUB);
            }
            case DfcNode.Mul m -> {
                emitScalarNode(mv, m.left(), xSlot, ySlot, zSlot, seedSlot);
                emitScalarNode(mv, m.right(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitInsn(DMUL);
            }
            case DfcNode.Negate n -> {
                emitScalarNode(mv, n.input(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitInsn(DNEG);
            }
            case DfcNode.Abs a -> {
                emitScalarNode(mv, a.input(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
            }
            case DfcNode.Min m -> {
                emitScalarNode(mv, m.left(), xSlot, ySlot, zSlot, seedSlot);
                emitScalarNode(mv, m.right(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            }
            case DfcNode.Max m -> {
                emitScalarNode(mv, m.left(), xSlot, ySlot, zSlot, seedSlot);
                emitScalarNode(mv, m.right(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
            }
            case DfcNode.Clamp c -> {
                emitScalarNode(mv, c.input(), xSlot, ySlot, zSlot, seedSlot);
                mv.visitLdcInsn(c.min());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                mv.visitLdcInsn(c.max());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            }

            case DfcNode.PerlinNoise n -> {
                mv.visitVarInsn(LLOAD, seedSlot);
                mv.visitLdcInsn(n.seedOffset());
                mv.visitInsn(LADD);
                emitScalarNode(mv, n.x(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                emitScalarNode(mv, n.y(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                emitScalarNode(mv, n.z(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                mv.visitMethodInsn(INVOKESTATIC, NOISE_RUNTIME, "perlin", "(JDDD)D", false);
            }
            case DfcNode.OctavePerlin n -> {
                mv.visitVarInsn(LLOAD, seedSlot);
                mv.visitLdcInsn(n.seedOffset());
                mv.visitInsn(LADD);
                mv.visitLdcInsn(n.octaves());
                mv.visitLdcInsn(n.persistence());
                mv.visitLdcInsn(n.lacunarity());
                emitScalarNode(mv, n.x(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                emitScalarNode(mv, n.y(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                emitScalarNode(mv, n.z(), xSlot, ySlot, zSlot, seedSlot); mv.visitLdcInsn(n.frequency()); mv.visitInsn(DMUL);
                mv.visitMethodInsn(INVOKESTATIC, NOISE_RUNTIME, "octavePerlin", "(JIDDDDD)D", false);
            }
        }
    }
}
