package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.AtlasMixinStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Times vanilla {@code NoiseBasedChunkGenerator.doFill(...)} per call so we can
 * compare on the user's actual machine against Atlas's JIT pipeline.
 * <p>
 * doFill is the synchronous noise-generation step inside fillFromNoise; it runs
 * on a worker thread and produces a single chunk's worth of terrain density.
 * Per-thread timing via ThreadLocal start markers + atomic accumulator.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", remap = false)
public abstract class NoiseChunkGenTimingMixin {

    private static final ThreadLocal<Long> ATLAS_DOFILL_START = new ThreadLocal<>();

    @Inject(method = "doFill*", at = @At("HEAD"), require = 0)
    private void atlas$doFillEnter(CallbackInfoReturnable<?> cir) {
        ATLAS_DOFILL_START.set(System.nanoTime());
    }

    @Inject(method = "doFill*", at = @At("RETURN"), require = 0)
    private void atlas$doFillExit(CallbackInfoReturnable<?> cir) {
        Long t0 = ATLAS_DOFILL_START.get();
        if (t0 != null) {
            AtlasMixinStats.recordDoFill(System.nanoTime() - t0);
            ATLAS_DOFILL_START.remove();
        }
    }
}
