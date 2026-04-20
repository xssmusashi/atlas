package dev.xssmusashi.atlas.mc;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters updated by Atlas mixins. Read-only access from {@code /atlas info}
 * proves whether mixins attached on the running MC version.
 */
public final class AtlasMixinStats {

    private AtlasMixinStats() {}

    private static final AtomicLong TICKS = new AtomicLong();
    private static final AtomicLong NOISE_INTERCEPTS = new AtomicLong();

    public static void recordTick() { TICKS.incrementAndGet(); }
    public static void recordNoiseIntercept() { NOISE_INTERCEPTS.incrementAndGet(); }

    public static long ticks() { return TICKS.get(); }
    public static long noiseIntercepts() { return NOISE_INTERCEPTS.get(); }

    public static boolean serverTickMixinActive() { return TICKS.get() > 0; }
}
