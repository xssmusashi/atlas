package dev.xssmusashi.atlas.mc;

import dev.xssmusashi.atlas.mc.api.AtlasService;
import dev.xssmusashi.atlas.mc.compat.C2MEDetector;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atlas Fabric mod entry point.
 * <p>
 * Phase 1 ships the worldgen kernel (atlas-core), Fabric integration scaffolding,
 * and the {@link AtlasService} public API. The vanilla ChunkGenerator subclass
 * that wires Atlas into MC's worldgen pipeline is implemented in Phase 2.
 */
public final class AtlasMod implements ModInitializer {

    public static final String MOD_ID = "atlas";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        C2MEDetector.checkAndFailFast();
        AtlasService service = AtlasService.get(); // touch to verify wiring
        LOG.info("Atlas initialised — service ready: {}. ChunkGenerator integration: Phase 2.",
            service.getClass().getSimpleName());
    }
}
