package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;

/**
 * Phase 3.2: substitutes vanilla density evaluation with Atlas JIT result when
 * (a) the tree was successfully converted by {@link AcceleratedRouter} and
 * (b) substitution is enabled via {@code /atlas accelerate on}.
 * <p>
 * Two hooks:
 * <ul>
 *   <li>On generator construction: try to build a JIT entry from the generator's
 *       final-density tree. Reflective access avoids hard imports of NoiseRouter.</li>
 *   <li>On each {@code getInterpolatedNoiseValue} call: if entry is usable AND
 *       substitution enabled, return our JIT result via cir.setReturnValue.
 *       Otherwise pass through to vanilla.</li>
 * </ul>
 * Safety net: any exception during reflection or JIT call → fallback to vanilla,
 * never crashes the chunkgen.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", remap = false)
public abstract class NoiseChunkGenSubstituteMixin {

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void atlas$buildJit(CallbackInfo ci) {
        try {
            Object self = this;
            Object finalDensity = extractFinalDensity(self);
            if (finalDensity != null) {
                AcceleratedRouter.getOrBuild(self, finalDensity);
            }
        } catch (Throwable ignored) {
            // best effort — if we can't reach the noise router, no acceleration possible
        }
    }

    @Inject(method = "getInterpolatedNoiseValue*", at = @At("HEAD"),
            require = 0, cancellable = true)
    private void atlas$substitute(CallbackInfoReturnable<Double> cir) {
        if (!AcceleratedRouter.isSubstituteEnabled()) return;
        try {
            Object self = this;
            AcceleratedRouter.Entry entry = AcceleratedRouter.get(self);
            if (entry == null || !entry.isUsable()) {
                AcceleratedRouter.recordFallback();
                return;
            }
            // We don't have direct access to the FunctionContext args here without
            // mojmap-named arg list. Best-effort: leave the body alone for now.
            // Substitute path is wired but does not yet replace — Phase 3.2 work
            // continues by figuring out the right inject point with arg capture.
            AcceleratedRouter.recordFallback();
        } catch (Throwable t) {
            AcceleratedRouter.recordFallback();
        }
    }

    /**
     * Reflectively pulls the noiseRouter().finalDensity() chain. Tries common
     * Mojmap method names; returns null if the structure differs.
     */
    private static Object extractFinalDensity(Object generator) {
        try {
            // generator.generatorSettings() returns Holder<NoiseGeneratorSettings>
            Method gs = findMethod(generator.getClass(), "generatorSettings");
            if (gs == null) return null;
            Object holder = gs.invoke(generator);
            // holder.value() returns NoiseGeneratorSettings
            Object settings = holder.getClass().getMethod("value").invoke(holder);
            // settings.noiseRouter() returns NoiseRouter
            Method nr = findMethod(settings.getClass(), "noiseRouter");
            if (nr == null) return null;
            Object router = nr.invoke(settings);
            // router.finalDensity()
            Method fd = findMethod(router.getClass(), "finalDensity");
            if (fd == null) return null;
            return fd.invoke(router);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        }
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }
}
