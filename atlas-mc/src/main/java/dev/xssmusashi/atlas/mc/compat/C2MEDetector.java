package dev.xssmusashi.atlas.mc.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Aborts mod initialisation if C2ME is also installed: both projects rewrite the
 * same chunk-loading code paths, which would silently corrupt worlds.
 */
public final class C2MEDetector {

    private C2MEDetector() {}

    private static final String[] BLOCKED_MODS = {
        "c2me",
        "c2me-base",
        "c2me-fabric"
    };

    public static void checkAndFailFast() {
        FabricLoader loader = FabricLoader.getInstance();
        for (String id : BLOCKED_MODS) {
            if (loader.isModLoaded(id)) {
                throw new RuntimeException(
                    "[Atlas] FATAL: detected '" + id + "' (C2ME).\n"
                  + "[Atlas] Atlas and C2ME both rewrite chunk loading and cannot coexist.\n"
                  + "[Atlas] Remove either C2ME or Atlas from your mods/ folder."
                );
            }
        }
    }
}
