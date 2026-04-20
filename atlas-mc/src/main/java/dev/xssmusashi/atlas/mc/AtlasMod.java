package dev.xssmusashi.atlas.mc;

/**
 * Atlas Fabric mod entry point.
 * <p>
 * Sub-plan 1 stub — does nothing yet. Sub-plan 6 wires this up as a real
 * {@code ModInitializer}, registers {@code AtlasChunkGenerator}, and adds
 * Fabric Loom + MC dependencies to {@code atlas-mc/build.gradle.kts}.
 */
public final class AtlasMod {

    public static final String MOD_ID = "atlas";

    private AtlasMod() {}

    public static String greeting() {
        return "Atlas mod stub — sub-plan 1 bootstrap. Worldgen integration arrives in sub-plan 6.";
    }
}
