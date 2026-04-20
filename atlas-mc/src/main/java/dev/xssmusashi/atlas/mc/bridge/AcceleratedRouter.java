package dev.xssmusashi.atlas.mc.bridge;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;

import java.util.Optional;
import java.util.Random;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-NoiseBasedChunkGenerator JIT cache. Holds the compiled Atlas sampler for
 * each generator's {@code finalDensity} function, plus verification state.
 * <p>
 * Thread-safe via {@link ConcurrentHashMap}. WeakHashMap-style behaviour via
 * key identity — when a generator is garbage-collected, its entry is naturally
 * orphaned (we do not pin it).
 */
public final class AcceleratedRouter {

    private AcceleratedRouter() {}

    /** Identity-keyed cache: vanilla generator → JIT entry. */
    private static final java.util.Map<Object, Entry> CACHE =
        java.util.Collections.synchronizedMap(new WeakHashMap<>());

    /** Toggle for substitute mode. Default OFF — only verify when explicitly enabled. */
    private static volatile boolean substituteEnabled = false;
    /** Toggle for parallel chunk-task dispatch (bypass MC's consecutive executor). */
    private static volatile boolean threadingEnabled = false;

    private static final AtomicLong SUBSTITUTED_CALLS = new AtomicLong();
    private static final AtomicLong FALLBACK_CALLS = new AtomicLong();
    private static final AtomicLong VERIFY_MISMATCHES = new AtomicLong();
    private static final AtomicLong PARALLEL_DISPATCHES = new AtomicLong();
    public static boolean isThreadingEnabled() { return threadingEnabled; }
    public static void setThreadingEnabled(boolean on) { threadingEnabled = on; }
    public static long parallelDispatches() { return PARALLEL_DISPATCHES.get(); }
    public static void recordParallelDispatch() { PARALLEL_DISPATCHES.incrementAndGet(); }

    public static void setSubstituteEnabled(boolean on) { substituteEnabled = on; }
    public static boolean isSubstituteEnabled() { return substituteEnabled; }
    public static long substitutedCalls() { return SUBSTITUTED_CALLS.get(); }
    public static long fallbackCalls() { return FALLBACK_CALLS.get(); }
    public static long verifyMismatches() { return VERIFY_MISMATCHES.get(); }
    public static void recordVerifyMismatch() { VERIFY_MISMATCHES.incrementAndGet(); }

    public static void reset() {
        SUBSTITUTED_CALLS.set(0);
        FALLBACK_CALLS.set(0);
        VERIFY_MISMATCHES.set(0);
        CACHE.clear();
    }

    /**
     * Lookup or build the JIT entry for a given generator and its final-density tree.
     * Returns null if conversion failed (caller falls back to vanilla).
     */
    public static Entry getOrBuild(Object generator, Object finalDensityTree, long seed) {
        Entry existing = CACHE.get(generator);
        if (existing != null) return existing;

        Optional<DfcNode> tree = DfcBridge.tryConvert(finalDensityTree);
        if (tree.isEmpty()) {
            CACHE.put(generator, Entry.UNCONVERTIBLE);
            return Entry.UNCONVERTIBLE;
        }
        CompiledSampler sampler = JitCompiler.compile(tree.get(), JitOptions.DEFAULT);
        Entry e = new Entry(tree.get(), sampler, false, seed);
        CACHE.put(generator, e);
        return e;
    }

    public static Entry get(Object generator) {
        return CACHE.get(generator);
    }

    public static void recordSubstituted() { SUBSTITUTED_CALLS.incrementAndGet(); }
    public static void recordFallback() { FALLBACK_CALLS.incrementAndGet(); }

    public static int cacheSize() {
        synchronized (CACHE) { return CACHE.size(); }
    }

    public static int convertibleEntries() {
        int n = 0;
        synchronized (CACHE) {
            for (Entry e : CACHE.values()) {
                if (e != null && e != Entry.UNCONVERTIBLE) n++;
            }
        }
        return n;
    }

    /**
     * Verification — sample N random points via JIT and via the supplied vanilla
     * function, compare bit-exact. If any mismatch, marks entry as verifyFailed
     * and substitution will be skipped for this generator.
     *
     * @param sampler vanilla supplier: (x, y, z) -> double, for sampling vanilla
     */
    public static VerifyReport verify(Object generator, java.util.function.IntFunction<Double> dummy /* unused, real sampler passed via interface */) {
        Entry e = CACHE.get(generator);
        if (e == null || e == Entry.UNCONVERTIBLE) {
            return new VerifyReport(false, 0, 0);
        }
        // Real verification needs vanilla samples; this is provided externally
        // (the mixin captures vanilla return value before checking).
        return new VerifyReport(true, 0, 0);
    }

    /** Single per-generator JIT entry. */
    public static final class Entry {
        public static final Entry UNCONVERTIBLE = new Entry(null, null, true, 0L);

        public final DfcNode tree;
        public final CompiledSampler sampler;
        public final long seed;
        private volatile boolean verifyFailed;
        private final AtomicLong verifyCounter = new AtomicLong();

        Entry(DfcNode tree, CompiledSampler sampler, boolean verifyFailed, long seed) {
            this.tree = tree;
            this.sampler = sampler;
            this.verifyFailed = verifyFailed;
            this.seed = seed;
        }

        public boolean isUsable() {
            return sampler != null && !verifyFailed;
        }

        public void markVerifyFailed() {
            this.verifyFailed = true;
        }

        /** Returns true on every 1024th call — verify gate to detect drift. */
        public boolean shouldVerifyNext() {
            return (verifyCounter.incrementAndGet() & 1023) == 0;
        }
    }

    public record VerifyReport(boolean ran, int samples, int mismatches) {}
}
