package dev.xssmusashi.atlas.mc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters updated by Atlas mixins. Read-only access from {@code /atlas info}
 * proves whether mixins attached on the running MC version.
 */
public final class AtlasMixinStats {

    private AtlasMixinStats() {}

    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicLong CHUNK_GEN_SEEN = new AtomicLong();
    private static final AtomicLong NOISE_POPULATE_INTERCEPTS = new AtomicLong();

    public static void recordTick() { TICKS.incrementAndGet(); }
    public static void recordChunkGenSeen() { CHUNK_GEN_SEEN.incrementAndGet(); }
    public static void recordNoisePopulate() { NOISE_POPULATE_INTERCEPTS.incrementAndGet(); }

    public static long ticks() { return TICKS.get(); }
    public static long chunkGenSeen() { return CHUNK_GEN_SEEN.get(); }
    public static long noisePopulateIntercepts() { return NOISE_POPULATE_INTERCEPTS.get(); }

    public static boolean serverTickMixinActive() { return TICKS.get() > 0; }
    public static boolean chunkGenMixinActive() { return CHUNK_GEN_SEEN.get() > 0; }
    public static boolean noisePopulateMixinActive() { return NOISE_POPULATE_INTERCEPTS.get() > 0; }
}
