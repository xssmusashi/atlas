package dev.xssmusashi.atlas.mc;

import dev.xssmusashi.atlas.mc.compat.C2MEDetector;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atlas Fabric mod entry point.
 * <p>
 * Sub-plan 6: detects C2ME conflict and logs initialisation. Real worldgen
 * registration (AtlasChunkGenerator + world preset) is wired in sub-plan 7.
 */
public final class AtlasMod implements ModInitializer {

    public static final String MOD_ID = "atlas";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        C2MEDetector.checkAndFailFast();
        LOG.info("Atlas initialised. Worldgen registration arrives in sub-plan 7.");
    }
}
