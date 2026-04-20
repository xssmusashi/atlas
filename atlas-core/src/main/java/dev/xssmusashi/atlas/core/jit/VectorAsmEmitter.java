package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Emits a Java class that uses {@link jdk.incubator.vector.DoubleVector} for the
 * {@link CompiledSampler#sampleSlice16(int, int, int, long, double[])} method.
 * <p>
 * The single-point {@code sample} method is still emitted as scalar bytecode (one
 * SIMD lane is wasteful for a single value) — Vector only kicks in for the slice path.
 * <p>
 * Phase 1 limitation: nodes that require irregular memory access (PerlinNoise,
 * OctavePerlin) cannot be vectorised cheaply. Trees containing them must use
 * {@link ScalarAsmEmitter}; {@link #supports(DfcNode)} is the gate.
 * <p>
 * The generated slice loop processes one species-width segment at a time. With
 * {@code DoubleVector.SPECIES_PREFERRED} this is 4 lanes on AVX2 and 8 on AVX-512.
 */
final class VectorAsmEmitter implements Opcodes {

    private VectorAsmEmitter() {}

    private static final String DOUBLE_VECTOR    = "jdk/incubator/vector/DoubleVector";
    private static final String VECTOR_SPECIES   = "jdk/incubator/vector/VectorSpecies";
    private static final String VECTOR_OPERATORS = "jdk/incubator/vector/VectorOperators";

    /** Returns true iff every node in the tree has a Vector implementation. */
    static boolean supports(DfcNode node) {
        return switch (node) {
            case DfcNode.Constant ignored -> true;
            case DfcNode.XPos     ignored -> true;
            case DfcNode.YPos     ignored -> true;
            case DfcNode.ZPos     ignored -> true;
            case DfcNode.Add a -> supports(a.left()) && supports(a.right());
            case DfcNode.Sub s -> supports(s.left()) && supports(s.right());
            case DfcNode.Mul m -> supports(m.left()) && supports(m.right());
            case DfcNode.Negate n -> supports(n.input());
            case DfcNode.Abs a -> supports(a.input());
            case DfcNode.Min m -> supports(m.left()) && supports(m.right());
            case DfcNode.Max m -> supports(m.left()) && supports(m.right());
            case DfcNode.Clamp c -> supports(c.input());
            case DfcNode.PerlinNoise ignored -> false;   // gather lookup blocks SIMD
            case DfcNode.OctavePerlin ignored -> false;
        };
    }

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

        // SPECIES is a static field, initialised in <clinit>.
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
            "SPECIES", "L" + VECTOR_SPECIES + ";", null, null).visitEnd();
        // INDEX_VEC = [0.0, 1.0, ..., VLEN-1.0] for converting baseX into per-lane X.
        cw.visitField(ACC_PRIVATE | ACC_STATIC | ACC_FINAL,
            "INDEX_VEC", "L" + DOUBLE_VECTOR + ";", null, null).visitEnd();

        emitClinit(cw, internalName);
        emitDefaultConstructor(cw);
        emitScalarSample(cw, root);
        emitVectorSlice(cw, root, internalName);

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void emitClinit(ClassWriter cw, String internalName) {
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(GETSTATIC, DOUBLE_VECTOR, "SPECIES_PREFERRED", "L" + VECTOR_SPECIES + ";");
        mv.visitInsn(DUP);
        mv.visitFieldInsn(PUTSTATIC, internalName, "SPECIES", "L" + VECTOR_SPECIES + ";");
        // Build INDEX_VEC: allocate double[length()] filled with 0..length-1, fromArray.
        mv.visitMethodInsn(INVOKEINTERFACE, VECTOR_SPECIES, "length", "()I", true);
        mv.visitInsn(DUP);
        mv.visitIntInsn(NEWARRAY, T_DOUBLE);
        mv.visitVarInsn(ASTORE, 1); // [length, double[]]
        // Loop fill: for (int i = 0; i < length; i++) arr[i] = i;
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 2);
        org.objectweb.asm.Label loop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label exit = new org.objectweb.asm.Label();
        mv.visitLabel(loop);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(SWAP);
        mv.visitInsn(DUP_X1);
        mv.visitInsn(SWAP); // [length, length, i]
        mv.visitJumpInsn(IF_ICMPLE, exit); // if length <= i exit
        mv.visitVarInsn(ALOAD, 1);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(I2D);
        mv.visitInsn(DASTORE);
        mv.visitIincInsn(2, 1);
        mv.visitJumpInsn(GOTO, loop);
        mv.visitLabel(exit);
        // Pop the leftover length; load species + array; INDEX_VEC = DoubleVector.fromArray(species, arr, 0)
        mv.visitInsn(POP);
        mv.visitFieldInsn(GETSTATIC, internalName, "SPECIES", "L" + VECTOR_SPECIES + ";");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "fromArray",
            "(L" + VECTOR_SPECIES + ";[DI)L" + DOUBLE_VECTOR + ";", false);
        mv.visitFieldInsn(PUTSTATIC, internalName, "INDEX_VEC", "L" + DOUBLE_VECTOR + ";");
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
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

    /** Reuses {@link ScalarAsmEmitter} pattern for single-point sample. */
    private static void emitScalarSample(ClassWriter cw, DfcNode root) {
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "sample", "(IIIJ)D", null, null);
        mv.visitCode();
        ScalarAsmEmitterShared.emitScalarNode(mv, root, 1, 2, 3, 4);
        mv.visitInsn(DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emits {@code sampleSlice16(int baseX, int y, int baseZ, long seed, double[] out)} as:
     * <pre>
     * for (int dz = 0; dz &lt; 16; dz++) {
     *     DoubleVector zVec = DoubleVector.broadcast(SPECIES, baseZ + dz);
     *     DoubleVector yVec = DoubleVector.broadcast(SPECIES, y);
     *     int vlen = SPECIES.length();
     *     for (int dx = 0; dx &lt; 16; dx += vlen) {
     *         DoubleVector xVec = DoubleVector.broadcast(SPECIES, baseX + dx).add(INDEX_VEC);
     *         &lt;tree result&gt;.intoArray(out, dz * 16 + dx);
     *     }
     * }
     * </pre>
     */
    private static void emitVectorSlice(ClassWriter cw, DfcNode root, String internalName) {
        // signature matches CompiledSampler.sampleSlice16
        MethodVisitor mv = cw.visitMethod(
            ACC_PUBLIC | ACC_FINAL,
            "sampleSlice16",
            "(IIIJ[D)V",
            null,
            null
        );
        mv.visitCode();

        // Locals layout:
        // 0: this, 1: baseX, 2: y, 3: baseZ, 4-5: seed (long), 6: out
        // Reserved further: 7: SPECIES, 8: vlen, 9: dz, 10: dx, 11: zVec, 12: yVec
        mv.visitFieldInsn(GETSTATIC, internalName, "SPECIES", "L" + VECTOR_SPECIES + ";");
        mv.visitVarInsn(ASTORE, 7);
        mv.visitVarInsn(ALOAD, 7);
        mv.visitMethodInsn(INVOKEINTERFACE, VECTOR_SPECIES, "length", "()I", true);
        mv.visitVarInsn(ISTORE, 8);

        // for (int dz = 0; dz < 16; dz++)
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 9);
        org.objectweb.asm.Label dzLoop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label dzExit = new org.objectweb.asm.Label();
        mv.visitLabel(dzLoop);
        mv.visitVarInsn(ILOAD, 9);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitJumpInsn(IF_ICMPGE, dzExit);

        // zVec = DoubleVector.broadcast(SPECIES, (double)(baseZ + dz))
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ILOAD, 3);
        mv.visitVarInsn(ILOAD, 9);
        mv.visitInsn(IADD);
        mv.visitInsn(I2D);
        mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
            "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
        mv.visitVarInsn(ASTORE, 11);

        // yVec = DoubleVector.broadcast(SPECIES, (double) y)
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ILOAD, 2);
        mv.visitInsn(I2D);
        mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
            "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
        mv.visitVarInsn(ASTORE, 12);

        // for (int dx = 0; dx < 16; dx += vlen)
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ISTORE, 10);
        org.objectweb.asm.Label dxLoop = new org.objectweb.asm.Label();
        org.objectweb.asm.Label dxExit = new org.objectweb.asm.Label();
        mv.visitLabel(dxLoop);
        mv.visitVarInsn(ILOAD, 10);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitJumpInsn(IF_ICMPGE, dxExit);

        // xVec = DoubleVector.broadcast(SPECIES, (double)(baseX + dx)).add(INDEX_VEC)
        mv.visitVarInsn(ALOAD, 7);
        mv.visitVarInsn(ILOAD, 1);
        mv.visitVarInsn(ILOAD, 10);
        mv.visitInsn(IADD);
        mv.visitInsn(I2D);
        mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
            "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
        mv.visitFieldInsn(GETSTATIC, internalName, "INDEX_VEC", "L" + DOUBLE_VECTOR + ";");
        mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "add",
            "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
        // [xVec] on stack; emit tree which consumes/produces vectors using the four locals 11/12/?
        // We need xVec on stack as part of sub-emission, but emitVectorNode below loads from a local.
        mv.visitVarInsn(ASTORE, 13);

        // Emit tree → DoubleVector on stack.
        // Slot layout: 11 = zVec, 12 = yVec, 13 = xVec.
        emitVectorNode(mv, root, internalName, /* yVecSlot */ 12, /* zVecSlot */ 11, /* xVecSlot */ 13);

        // .intoArray(out, dz * 16 + dx)
        mv.visitVarInsn(ALOAD, 6);
        mv.visitVarInsn(ILOAD, 9);
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitInsn(IMUL);
        mv.visitVarInsn(ILOAD, 10);
        mv.visitInsn(IADD);
        mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "intoArray", "([DI)V", false);

        // dx += vlen
        mv.visitVarInsn(ILOAD, 10);
        mv.visitVarInsn(ILOAD, 8);
        mv.visitInsn(IADD);
        mv.visitVarInsn(ISTORE, 10);
        mv.visitJumpInsn(GOTO, dxLoop);
        mv.visitLabel(dxExit);

        // dz++
        mv.visitIincInsn(9, 1);
        mv.visitJumpInsn(GOTO, dzLoop);
        mv.visitLabel(dzExit);

        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /**
     * Emit DoubleVector-producing bytecode for a node. Locals:
     * yVec at slot {@code yVecSlot}, zVec at {@code zVecSlot}, xVec at {@code xVecSlot}.
     */
    private static void emitVectorNode(MethodVisitor mv, DfcNode node, String selfName,
                                       int yVecSlot, int zVecSlot, int xVecSlot) {
        switch (node) {
            case DfcNode.Constant c -> {
                mv.visitFieldInsn(GETSTATIC, selfName, "SPECIES", "L" + VECTOR_SPECIES + ";");
                mv.visitLdcInsn(c.value());
                mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
                    "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.XPos ignored -> mv.visitVarInsn(ALOAD, xVecSlot);
            case DfcNode.YPos ignored -> mv.visitVarInsn(ALOAD, yVecSlot);
            case DfcNode.ZPos ignored -> mv.visitVarInsn(ALOAD, zVecSlot);

            case DfcNode.Add a -> {
                emitVectorNode(mv, a.left(), selfName, yVecSlot, zVecSlot, xVecSlot);
                emitVectorNode(mv, a.right(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "add",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Sub s -> {
                emitVectorNode(mv, s.left(), selfName, yVecSlot, zVecSlot, xVecSlot);
                emitVectorNode(mv, s.right(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "sub",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Mul m -> {
                emitVectorNode(mv, m.left(), selfName, yVecSlot, zVecSlot, xVecSlot);
                emitVectorNode(mv, m.right(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "mul",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Negate n -> {
                emitVectorNode(mv, n.input(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "neg",
                    "()L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Abs a -> {
                emitVectorNode(mv, a.input(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "abs",
                    "()L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Min m -> {
                emitVectorNode(mv, m.left(), selfName, yVecSlot, zVecSlot, xVecSlot);
                emitVectorNode(mv, m.right(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "min",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Max m -> {
                emitVectorNode(mv, m.left(), selfName, yVecSlot, zVecSlot, xVecSlot);
                emitVectorNode(mv, m.right(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "max",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.Clamp c -> {
                emitVectorNode(mv, c.input(), selfName, yVecSlot, zVecSlot, xVecSlot);
                mv.visitFieldInsn(GETSTATIC, selfName, "SPECIES", "L" + VECTOR_SPECIES + ";");
                mv.visitLdcInsn(c.min());
                mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
                    "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "max",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
                mv.visitFieldInsn(GETSTATIC, selfName, "SPECIES", "L" + VECTOR_SPECIES + ";");
                mv.visitLdcInsn(c.max());
                mv.visitMethodInsn(INVOKESTATIC, DOUBLE_VECTOR, "broadcast",
                    "(L" + VECTOR_SPECIES + ";D)L" + DOUBLE_VECTOR + ";", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, DOUBLE_VECTOR, "min",
                    "(Ljdk/incubator/vector/Vector;)L" + DOUBLE_VECTOR + ";", false);
            }
            case DfcNode.PerlinNoise ignored ->
                throw new IllegalStateException("PerlinNoise not vectorisable; check supports() first");
            case DfcNode.OctavePerlin ignored ->
                throw new IllegalStateException("OctavePerlin not vectorisable; check supports() first");
        }
    }
}
