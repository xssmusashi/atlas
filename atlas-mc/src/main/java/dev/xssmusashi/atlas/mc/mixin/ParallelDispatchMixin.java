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
 * v3.1.1 — main worldgen acceleration win, narrowly scoped.
 * <p>
 * MC 26.1 has TWO consecutive executors that subclass AbstractConsecutiveExecutor:
 * <ul>
 *   <li>{@code PriorityConsecutiveExecutor} — used by ChunkTaskDispatcher for worldgen.
 *       SAFE to parallelise: per-chunk ordering is enforced by CompletableFuture chains.</li>
 *   <li>{@code ConsecutiveExecutor} — used by IOWorker. NOT SAFE to parallelise:
 *       its internal state (LinkedHashMap of pending writes) requires serial access,
 *       crashes with ConcurrentModificationException if multiple threads execute it.</li>
 * </ul>
 * v3.1 mistakenly targeted the parent class — broke IOWorker. v3.1.1 narrows to
 * PriorityConsecutiveExecutor only. Mixin on the inherited schedule() method works
 * because Mixin injects in the target-class context.
 * <p>
 * When Atlas threading mode is enabled, this mixin bypasses the consecutive queue
 * and immediately dispatches each scheduled task to the underlying multi-threaded
 * executor. Per-chunk task ordering is preserved by CompletableFuture chains.
 * <p>
 * Default: OFF. Enable via {@code /atlas accelerate threading on}. If anything
 * misbehaves, {@code off} restores vanilla consecutive behaviour instantly.
 */
@Mixin(targets = "net.minecraft.util.thread.PriorityConsecutiveExecutor", remap = false)
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
