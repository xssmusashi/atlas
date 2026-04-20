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
 * Phase 1: only {@link DfcNode.Constant} supported. The generated class has shape:
 * <pre>
 *   public final class AtlasJit$N implements CompiledSampler {
 *       public double sample(int x, int y, int z, long seed) {
 *           return &lt;constant&gt;;
 *       }
 *   }
 * </pre>
 */
final class ScalarAsmEmitter implements Opcodes {

    private ScalarAsmEmitter() {}

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
        }
    }
}
