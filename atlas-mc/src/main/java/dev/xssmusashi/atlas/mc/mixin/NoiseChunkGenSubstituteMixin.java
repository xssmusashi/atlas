package dev.xssmusashi.atlas.mc.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Phase 3 substitute mixin. Two hooks:
 * <ol>
 *   <li>At constructor RETURN: extract the generator's noiseRouter().finalDensity()
 *       and seed via reflection, build/cache an Atlas JIT entry.</li>
 *   <li>At {@code getInterpolatedNoiseValue} RETURN (via mixin-extras
 *       {@code @ModifyReturnValue}): if substitute mode ON and entry usable,
 *       compute via JIT and return that instead. Falls back to vanilla on any
 *       error.</li>
 * </ol>
 * Reflective extraction means we never hard-import RandomState/FunctionContext —
 * MC version differences don't break compilation.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", remap = false)
public abstract class NoiseChunkGenSubstituteMixin {

    private static volatile Method ctxBlockX;
    private static volatile Method ctxBlockY;
    private static volatile Method ctxBlockZ;

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void atlas$buildJit(CallbackInfo ci) {
        try {
            Object self = this;
            Object finalDensity = extractFinalDensity(self);
            long seed = extractSeed(self);
            if (finalDensity != null) {
                AcceleratedRouter.getOrBuild(self, finalDensity, seed);
            }
        } catch (Throwable ignored) {}
    }

    @ModifyReturnValue(method = "getInterpolatedNoiseValue", at = @At("RETURN"), require = 0)
    private double atlas$replaceNoise(double original, Object randomState, Object functionContext) {
        if (!AcceleratedRouter.isSubstituteEnabled()) return original;
        try {
            Object self = this;
            AcceleratedRouter.Entry entry = AcceleratedRouter.get(self);
            if (entry == null || !entry.isUsable()) {
                AcceleratedRouter.recordFallback();
                return original;
            }
            int x = extractCoord(functionContext, 0);
            int y = extractCoord(functionContext, 1);
            int z = extractCoord(functionContext, 2);
            double atlasValue = entry.sampler.sample(x, y, z, entry.seed);
            // Optional verify: every Nth call, compare with original
            if (entry.shouldVerifyNext()) {
                if (Math.abs(atlasValue - original) > 0.001) {
                    AcceleratedRouter.recordVerifyMismatch();
                    entry.markVerifyFailed();
                    return original;
                }
            }
            AcceleratedRouter.recordSubstituted();
            return atlasValue;
        } catch (Throwable t) {
            AcceleratedRouter.recordFallback();
            return original;
        }
    }

    private static int extractCoord(Object ctx, int axis) throws Exception {
        Method m;
        switch (axis) {
            case 0:
                m = ctxBlockX;
                if (m == null) {
                    m = findMethod(ctx.getClass(), "blockX");
                    ctxBlockX = m;
                }
                break;
            case 1:
                m = ctxBlockY;
                if (m == null) {
                    m = findMethod(ctx.getClass(), "blockY");
                    ctxBlockY = m;
                }
                break;
            default:
                m = ctxBlockZ;
                if (m == null) {
                    m = findMethod(ctx.getClass(), "blockZ");
                    ctxBlockZ = m;
                }
        }
        return (int) m.invoke(ctx);
    }

    private static Object extractFinalDensity(Object generator) {
        try {
            Method gs = findMethod(generator.getClass(), "generatorSettings");
            if (gs == null) return null;
            Object holder = gs.invoke(generator);
            Object settings = holder.getClass().getMethod("value").invoke(holder);
            Method nr = findMethod(settings.getClass(), "noiseRouter");
            if (nr == null) return null;
            Object router = nr.invoke(settings);
            Method fd = findMethod(router.getClass(), "finalDensity");
            if (fd == null) return null;
            return fd.invoke(router);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Best-effort seed extraction for caching. Falls back to 0L if unknown. */
    private static long extractSeed(Object generator) {
        try {
            // Try common 'seed' field
            for (Class<?> c = generator.getClass(); c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() == long.class && (f.getName().equals("seed")
                            || f.getName().toLowerCase().contains("seed"))) {
                        f.setAccessible(true);
                        return f.getLong(generator);
                    }
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
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
