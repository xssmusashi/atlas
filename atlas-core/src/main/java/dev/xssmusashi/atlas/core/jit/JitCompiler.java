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
 */
public final class JitCompiler {

    private static final AtomicLong CLASS_COUNTER = new AtomicLong();
    private static final Lookup LOOKUP = MethodHandles.lookup();

    private JitCompiler() {}

    public static CompiledSampler compile(DfcNode root) {
        long id = CLASS_COUNTER.getAndIncrement();
        String internalName = "dev/xssmusashi/atlas/core/jit/AtlasJit_" + id;
        byte[] bytecode = ScalarAsmEmitter.emit(root, internalName);
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
