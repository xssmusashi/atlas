package dev.xssmusashi.atlas.mc;

import dev.xssmusashi.atlas.mc.api.AtlasService;
import dev.xssmusashi.atlas.mc.command.AtlasCommand;
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

        // /atlas command depends on MC's text/command classes whose intermediary
        // names differ between MC versions. The mod is built against MC 1.21.4
        // mappings; on other MC versions (e.g. 26.1.x with new intermediary numbers)
        // command class loading throws NoClassDefFoundError. Catch and continue —
        // the mod is still usable as a Fabric library / service.
        boolean commandsRegistered = tryRegisterCommands();

        LOG.info("Atlas initialised — service: {}, /atlas command: {}. ChunkGenerator: Phase 2.",
            service.getClass().getSimpleName(),
            commandsRegistered ? "registered" : "DISABLED (MC version mismatch — see logs)");
    }

    private static boolean tryRegisterCommands() {
        try {
            AtlasCommand.register();
            return true;
        } catch (Throwable t) {
            LOG.warn("[Atlas] /atlas command unavailable on this MC version: {} ({})",
                t.getClass().getSimpleName(), t.getMessage());
            LOG.warn("[Atlas] The mod was built for MC 1.21.4 yarn mappings. "
                + "Engine + AtlasService API still work; rebuild against your MC's yarn for /atlas commands.");
            return false;
        }
    }
}
