package dev.xssmusashi.atlas.mc.api;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import dev.xssmusashi.atlas.core.tile.TilePipeline;

/**
 * Public service API exposed by the Atlas Fabric mod.
 * <p>
 * Other Fabric mods (Farsight, custom worldgen mods, profiling tools) call into
 * Atlas without needing mixins or reflection. Obtain via:
 * <pre>{@code
 *   AtlasService atlas = AtlasService.get();
 *   CompiledSampler sampler = atlas.compile(myTree);
 *   TilePipeline pipeline = atlas.createPipeline(seed, sampler);
 * }</pre>
 * <p>
 * The full {@code AtlasChunkGenerator} integration that wires this service to the
 * vanilla {@code ChunkGenerator} pipeline is implemented in Phase 2 (dimensional
 * worldgen, biomes, surface, features). Phase 1 ships the building blocks.
 */
public final class AtlasService {

    private static final AtlasService INSTANCE = new AtlasService();

    public static AtlasService get() { return INSTANCE; }

    private AtlasService() {}

    /** Compile a DFC tree into a sampler (auto-selects scalar / vector emitter). */
    public CompiledSampler compile(DfcNode tree) {
        return JitCompiler.compile(tree);
    }

    /** Compile with explicit options. */
    public CompiledSampler compile(DfcNode tree, JitOptions options) {
        return JitCompiler.compile(tree, options);
    }

    /** Create a tile pipeline backed by the given sampler and a default scheduler. */
    public TilePipeline createPipeline(long seed, CompiledSampler sampler) {
        DagScheduler sched = DagScheduler.defaultPool();
        return new TilePipeline(seed, sampler, sched);
    }

    /** Create a tile pipeline with an explicit scheduler (caller owns lifecycle). */
    public TilePipeline createPipeline(long seed, CompiledSampler sampler, DagScheduler scheduler) {
        return new TilePipeline(seed, sampler, scheduler);
    }
}
