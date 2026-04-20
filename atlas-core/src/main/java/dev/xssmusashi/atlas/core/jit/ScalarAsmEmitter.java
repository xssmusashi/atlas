package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Emits a Java class implementing {@link CompiledSampler} by walking a {@link DfcNode}
 * tree and inlining the computation as scalar bytecode.
 * <p>
 * Generated class shape:
 * <pre>
 *   public final class AtlasJit_N implements CompiledSampler {
 *       public double sample(int x, int y, int z, long seed) {
 *           return &lt;inlined tree&gt;;
 *       }
 *   }
 * </pre>
 * Each {@link DfcNode} variant is emitted as its corresponding bytecode primitives —
 * no virtual dispatch, no switch, no record field access. JVM C2 inlines aggressively.
 */
final class ScalarAsmEmitter implements Opcodes {

    private ScalarAsmEmitter() {}

    /** Slot indices in {@code sample(int x, int y, int z, long seed)}. */
    private static final int X_SLOT = 1;
    private static final int Y_SLOT = 2;
    private static final int Z_SLOT = 3;
    // long seed occupies slots 4-5

    static byte[] emit(DfcNode root, String internalName) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(
            V21,
            ACC_PUBLIC | ACC_FINAL,
            internalName,
            null,
            "java/lang/Object",
            new String[]{Type.getInternalName(CompiledSampler.class)}
        );

        emitDefaultConstructor(cw);
        emitSampleMethod(cw, root);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitDefaultConstructor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 0);
        mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitSampleMethod(ClassWriter cw, DfcNode root) {
        MethodVisitor mv = cw.visitMethod(
            ACC_PUBLIC | ACC_FINAL,
            "sample",
            "(IIIJ)D",
            null,
            null
        );
        mv.visitCode();
        emitNode(mv, root);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    private static void emitNode(MethodVisitor mv, DfcNode node) {
        switch (node) {
            case DfcNode.Constant c -> mv.visitLdcInsn(c.value());

            case DfcNode.XPos ignored -> { mv.visitVarInsn(ILOAD, X_SLOT); mv.visitInsn(I2D); }
            case DfcNode.YPos ignored -> { mv.visitVarInsn(ILOAD, Y_SLOT); mv.visitInsn(I2D); }
            case DfcNode.ZPos ignored -> { mv.visitVarInsn(ILOAD, Z_SLOT); mv.visitInsn(I2D); }

            case DfcNode.Add a -> { emitNode(mv, a.left()); emitNode(mv, a.right()); mv.visitInsn(DADD); }
            case DfcNode.Sub s -> { emitNode(mv, s.left()); emitNode(mv, s.right()); mv.visitInsn(DSUB); }
            case DfcNode.Mul m -> { emitNode(mv, m.left()); emitNode(mv, m.right()); mv.visitInsn(DMUL); }
            case DfcNode.Negate n -> { emitNode(mv, n.input()); mv.visitInsn(DNEG); }
            case DfcNode.Abs a -> {
                emitNode(mv, a.input());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "abs", "(D)D", false);
            }
            case DfcNode.Min m -> {
                emitNode(mv, m.left());
                emitNode(mv, m.right());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            }
            case DfcNode.Max m -> {
                emitNode(mv, m.left());
                emitNode(mv, m.right());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
            }
            case DfcNode.Clamp c -> {
                emitNode(mv, c.input());
                mv.visitLdcInsn(c.min());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
                mv.visitLdcInsn(c.max());
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(DD)D", false);
            }
        }
    }
}
