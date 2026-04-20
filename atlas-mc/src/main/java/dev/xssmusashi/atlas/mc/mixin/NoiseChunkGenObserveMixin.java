package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.AtlasMixinStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Phase 1 of v1.6 worldgen integration: observe-only hooks into
 * {@code NoiseBasedChunkGenerator}. Targets the class by string so the mixin
 * is silently skipped at runtime if MC 26.1 has renamed/moved it (instead of
 * failing at build time). All injects use {@code require = 0} so a missing
 * method signature does not crash the mod — it just leaves the counter at zero.
 *
 * <p>If both counters tick up at runtime ({@code chunk_gen_seen > 0} and
 * {@code noise_populate > 0}), Phase 2 of v1.6 can safely substitute the JIT
 * sampler for vanilla density-function evaluation.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", remap = false)
public abstract class NoiseChunkGenObserveMixin {

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void atlas$onConstructed(CallbackInfo ci) {
        AtlasMixinStats.recordChunkGenSeen();
    }

    /**
     * Best-effort hook on populateNoise. We don't know the exact signature in
     * MC 26.1 — try the typical name; if no match, require=0 makes it silent.
     */
    @Inject(method = "populateNoise*", at = @At("HEAD"), require = 0)
    private void atlas$onPopulateNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }
}
