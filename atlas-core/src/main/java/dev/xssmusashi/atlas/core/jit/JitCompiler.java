package dev.xssmusashi.atlas.core.jit;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compiles {@link DfcNode} trees into JIT-emitted {@link CompiledSampler} instances.
 * <p>
 * Each compile call produces a hidden class loaded into this package via
 * {@link Lookup#defineHiddenClass(byte[], boolean, Lookup.ClassOption...)}.
 * <p>
 * Two emitters are available:
 * <ul>
 *   <li>{@link ScalarAsmEmitter} — works for any tree, single-point oriented.</li>
 *   <li>{@link VectorAsmEmitter} — uses {@code jdk.incubator.vector} for the slice
 *       path. Only used when the tree contains no noise nodes.</li>
 * </ul>
 */
public final class JitCompiler {

    private static final AtomicLong CLASS_COUNTER = new AtomicLong();
    private static final Lookup LOOKUP = MethodHandles.lookup();

    private JitCompiler() {}

    /** Default options (AUTO emitter). */
    public static CompiledSampler compile(DfcNode root) {
        return compile(root, JitOptions.DEFAULT);
    }

    public static CompiledSampler compile(DfcNode root, JitOptions options) {
        long id = CLASS_COUNTER.getAndIncrement();
        boolean useVector = switch (options.emitter()) {
            case SCALAR -> false;
            case VECTOR -> {
                if (!VectorAsmEmitter.supports(root)) {
                    throw new IllegalArgumentException(
                        "VECTOR emitter requested but tree contains unsupported nodes (e.g. PerlinNoise)");
                }
                yield true;
            }
            case AUTO -> VectorAsmEmitter.supports(root);
        };

        String internalName = "dev/xssmusashi/atlas/core/jit/AtlasJit_"
            + (useVector ? "v_" : "s_") + id;
        byte[] bytecode = useVector
            ? VectorAsmEmitter.emit(root, internalName)
            : ScalarAsmEmitter.emit(root, internalName);
        try {
            Class<?> cls = LOOKUP
                .defineHiddenClass(bytecode, true, Lookup.ClassOption.NESTMATE)
                .lookupClass();
            return (CompiledSampler) cls.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("JIT compilation failed for tree: " + root, e);
        }
    }
}
