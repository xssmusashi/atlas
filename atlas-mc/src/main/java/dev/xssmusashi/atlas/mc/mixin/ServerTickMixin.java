package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.AtlasMixinStats;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Phase 2.4 mixin foundation — hooks into MinecraftServer's tick loop and
 * increments a counter visible via {@code /atlas info}. Does NOT change
 * vanilla behaviour. Purpose: prove on the user's actual MC 26.1.1 that
 * Atlas's mixin pipeline attaches correctly. Phase 2.5 then replaces this
 * harmless counter with real interception of NoiseBasedChunkGenerator's
 * density function evaluation.
 */
@Mixin(MinecraftServer.class)
public abstract class ServerTickMixin {

    @Inject(method = "tickServer(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void atlas$onTickServer(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        AtlasMixinStats.recordTick();
    }
}
