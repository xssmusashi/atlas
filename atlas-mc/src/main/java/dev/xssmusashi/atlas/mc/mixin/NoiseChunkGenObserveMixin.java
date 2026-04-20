package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.AtlasMixinStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;

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

    private static final Logger DIAG = LoggerFactory.getLogger("atlas/diag");

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void atlas$onConstructed(CallbackInfo ci) {
        long seen = 1 + AtlasMixinStats.chunkGenSeen();
        AtlasMixinStats.recordChunkGenSeen();

        // ONE-SHOT diagnostic dump. The first time we see a NoiseBasedChunkGenerator,
        // log all its declared methods so we can identify what to hook in v1.6.1.
        if (seen == 1) {
            try {
                Class<?> cls = this.getClass();
                DIAG.info("[Atlas DIAG] target class: {}", cls.getName());
                DIAG.info("[Atlas DIAG] methods declared on NoiseBasedChunkGenerator:");
                Arrays.stream(cls.getDeclaredMethods())
                    .sorted(java.util.Comparator.comparing(java.lang.reflect.Method::getName))
                    .forEach(m -> DIAG.info("[Atlas DIAG]   {} {}({})",
                        m.getReturnType().getSimpleName(),
                        m.getName(),
                        Arrays.stream(m.getParameterTypes())
                            .map(Class::getSimpleName)
                            .reduce((a, b) -> a + ", " + b).orElse("")));
                DIAG.info("[Atlas DIAG] (above list is needed to wire v1.6.1 noise interception)");
            } catch (Throwable t) {
                DIAG.warn("[Atlas DIAG] method dump failed: {}", t.toString());
            }
        }
    }

    // Try several common names for the noise-population hot path.
    // Whichever one matches in MC 26.1 will tick the counter; the others are silent.

    @Inject(method = "populateNoise*", at = @At("HEAD"), require = 0)
    private void atlas$onPopulateNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }

    @Inject(method = "doFill*", at = @At("HEAD"), require = 0)
    private void atlas$onDoFill(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }

    @Inject(method = "iterateNoiseColumn*", at = @At("HEAD"), require = 0)
    private void atlas$onIterateNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }

    @Inject(method = "fillFromNoise*", at = @At("HEAD"), require = 0)
    private void atlas$onFillFromNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }

    @Inject(method = "generateNoise*", at = @At("HEAD"), require = 0)
    private void atlas$onGenerateNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }

    @Inject(method = "buildNoise*", at = @At("HEAD"), require = 0)
    private void atlas$onBuildNoise(CallbackInfoReturnable<?> cir) {
        AtlasMixinStats.recordNoisePopulate();
    }
}
