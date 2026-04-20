package dev.xssmusashi.atlas.core.jit;

/**
 * Compile-time options for {@link JitCompiler}.
 *
 * @param emitter which bytecode emitter to use
 */
public record JitOptions(Emitter emitter) {

    public enum Emitter {
        /** Scalar bytecode (DADD/DMUL/Math.x). Always works for any tree. */
        SCALAR,

        /**
         * Vector API bytecode (jdk.incubator.vector). Falls back to scalar if the
         * tree contains nodes Vector emitter does not yet support (e.g. PerlinNoise).
         */
        VECTOR,

        /** Default: pick scalar today; later versions may benchmark and choose. */
        AUTO
    }

    public static final JitOptions DEFAULT = new JitOptions(Emitter.AUTO);

    public static JitOptions scalar() { return new JitOptions(Emitter.SCALAR); }
    public static JitOptions vector() { return new JitOptions(Emitter.VECTOR); }
}
