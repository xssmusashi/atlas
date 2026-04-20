package dev.xssmusashi.atlas.mc.mixin;

import dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.Executor;

/**
 * v3.1 — main worldgen acceleration win.
 * <p>
 * MC 26.1 ChunkTaskDispatcher uses PriorityConsecutiveExecutor (a subclass of
 * AbstractConsecutiveExecutor) which processes scheduled tasks ONE AT A TIME,
 * sequentially through its underlying queue, even though the underlying
 * {@code Util.backgroundExecutor()} pool has nCpus-1 worker threads. This is
 * the bottleneck that limits live worldgen speed regardless of CPU count.
 * <p>
 * When Atlas threading mode is enabled (gated, opt-in), this mixin bypasses the
 * consecutive queue and immediately dispatches each scheduled task to the
 * underlying multi-threaded executor. The CompletableFuture chains MC uses
 * for per-chunk task ordering still enforce per-chunk sequencing internally.
 * <p>
 * Default: OFF. Enable via {@code /atlas accelerate threading on}. If anything
 * misbehaves, {@code off} restores vanilla consecutive behaviour instantly.
 */
@Mixin(targets = "net.minecraft.util.thread.AbstractConsecutiveExecutor", remap = false)
public abstract class ParallelDispatchMixin {

    @Shadow @Final
    private Executor executor;

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true, require = 0)
    private void atlas$parallelDispatch(Runnable task, CallbackInfo ci) {
        if (!AcceleratedRouter.isThreadingEnabled()) return;
        try {
            executor.execute(task);
            AcceleratedRouter.recordParallelDispatch();
            ci.cancel();
        } catch (Throwable t) {
            // Fall through to vanilla consecutive queue — never crash
        }
    }
}
