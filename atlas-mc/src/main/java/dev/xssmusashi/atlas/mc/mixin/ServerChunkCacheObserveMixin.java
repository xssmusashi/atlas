package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.AtlasMixinStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Arrays;

/**
 * Diagnostic mixin into ServerChunkCache. On first construction, dumps the
 * full method list to log so we can identify the right hook for v2.0 threading
 * rewrite (Sprint B) without an IDE.
 * <p>
 * Once we know the actual method signatures, this mixin can be replaced with
 * targeted @Redirect/@WrapMethod for chunk task dispatching.
 */
@Mixin(targets = "net.minecraft.server.level.ServerChunkCache", remap = false)
public abstract class ServerChunkCacheObserveMixin {

    private static final Logger DIAG = LoggerFactory.getLogger("atlas/diag");
    private static volatile boolean dumped = false;

    @Inject(method = "<init>*", at = @At("RETURN"), require = 0)
    private void atlas$dumpStructure(CallbackInfo ci) {
        if (dumped) return;
        dumped = true;
        try {
            Class<?> cls = this.getClass();
            DIAG.info("[Atlas DIAG] target class: {}", cls.getName());
            DIAG.info("[Atlas DIAG] ServerChunkCache methods (sorted):");
            Arrays.stream(cls.getDeclaredMethods())
                .sorted(java.util.Comparator.comparing(java.lang.reflect.Method::getName))
                .forEach(m -> DIAG.info("[Atlas DIAG]   {} {}({})",
                    m.getReturnType().getSimpleName(),
                    m.getName(),
                    Arrays.stream(m.getParameterTypes())
                        .map(Class::getSimpleName)
                        .reduce((a, b) -> a + ", " + b).orElse("")));
            DIAG.info("[Atlas DIAG] ServerChunkCache fields:");
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (var f : c.getDeclaredFields()) {
                    DIAG.info("[Atlas DIAG]   {} {} {}",
                        java.lang.reflect.Modifier.toString(f.getModifiers()),
                        f.getType().getSimpleName(),
                        f.getName());
                }
            }
            DIAG.info("[Atlas DIAG] (above is needed to wire Sprint B threading rewrite)");
            AtlasMixinStats.recordChunkSourceObserved();
        } catch (Throwable t) {
            DIAG.warn("[Atlas DIAG] ServerChunkCache dump failed: {}", t.toString());
        }
    }
}
