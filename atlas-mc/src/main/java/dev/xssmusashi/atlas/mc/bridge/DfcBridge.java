package dev.xssmusashi.atlas.mc.bridge;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

import java.util.Map;
import java.util.function.Function;

/**
 * Converts vanilla Minecraft {@code DensityFunction} instances into Atlas
 * {@link DfcNode} ASTs that the JIT can compile.
 * <p>
 * Phase 1 status: this class establishes the dispatch pattern. Concrete
 * conversion of all vanilla DFC subclasses (e.g. {@code DensityFunctionTypes.Add},
 * {@code DensityFunctionTypes.Noise}, {@code DensityFunctionTypes.Spline}, ...)
 * is implemented in Phase 2 once the AtlasChunkGenerator path goes live and we
 * can validate against a running server.
 * <p>
 * The pattern:
 * <pre>{@code
 *   DfcNode atlasTree = DfcBridge.convert(vanillaDensityFunction);
 *   CompiledSampler sampler = AtlasService.get().compile(atlasTree);
 * }</pre>
 *
 * For unknown node types the bridge throws {@link UnsupportedOperationException};
 * callers can catch it and fall back to vanilla generation per-tree.
 */
public final class DfcBridge {

    private DfcBridge() {}

    /**
     * Registry of vanilla DFC class name → conversion function. Phase 1 ships
     * an empty registry — Phase 2 populates it. We keep the registry indirection
     * so the conversion is data-driven and easily extended without touching the
     * dispatcher.
     */
    private static final Map<String, Function<Object, DfcNode>> CONVERTERS = Map.of();

    /**
     * Convert a vanilla DFC instance to an Atlas AST.
     *
     * @param vanillaDensityFunction an instance of net.minecraft.world.gen.densityfunction.DensityFunction
     * @return Atlas DFC node tree
     * @throws UnsupportedOperationException if the type is not yet recognised
     */
    public static DfcNode convert(Object vanillaDensityFunction) {
        if (vanillaDensityFunction == null) {
            throw new IllegalArgumentException("vanillaDensityFunction is null");
        }
        String className = vanillaDensityFunction.getClass().getName();
        Function<Object, DfcNode> converter = CONVERTERS.get(className);
        if (converter == null) {
            throw new UnsupportedOperationException(
                "DfcBridge does not yet support vanilla DFC type: " + className
              + " (Phase 2 will add full conversion; for now, fall back to vanilla)."
            );
        }
        return converter.apply(vanillaDensityFunction);
    }

    /**
     * Whether the bridge can convert this vanilla DFC instance. Useful for
     * deciding whether to JIT-compile or fall back to vanilla per-tree.
     */
    public static boolean canConvert(Object vanillaDensityFunction) {
        return vanillaDensityFunction != null
            && CONVERTERS.containsKey(vanillaDensityFunction.getClass().getName());
    }
}
