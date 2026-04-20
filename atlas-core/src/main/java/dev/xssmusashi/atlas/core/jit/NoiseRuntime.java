package dev.xssmusashi.atlas.core.jit;

import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Noise primitives invoked from JIT-emitted bytecode and from the interpreter.
 * <p>
 * Implements Ken Perlin's improved 3D noise (2002) with deterministic
 * per-seed permutation table. All methods are pure functions of (seed, x, y, z).
 * <p>
 * Permutation tables are cached per seed (~2 KB each, bounded LRU).
 */
public final class NoiseRuntime {

    private NoiseRuntime() {}

    private static final int CACHE_LIMIT = 64;

    /** seed → permutation table of length 512 (doubled to skip mod 256). */
    private static final ConcurrentHashMap<Long, int[]> PERM_CACHE = new ConcurrentHashMap<>();

    private static int[] perm(long seed) {
        int[] cached = PERM_CACHE.get(seed);
        if (cached != null) return cached;
        // Bound the cache cheaply (clear on overflow, rebuild lazily).
        if (PERM_CACHE.size() > CACHE_LIMIT) PERM_CACHE.clear();
        int[] p = generatePermutation(seed);
        PERM_CACHE.putIfAbsent(seed, p);
        return p;
    }

    private static int[] generatePermutation(long seed) {
        int[] p = new int[256];
        for (int i = 0; i < 256; i++) p[i] = i;
        SplittableRandom rng = new SplittableRandom(seed);
        for (int i = 255; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = p[i]; p[i] = p[j]; p[j] = tmp;
        }
        int[] doubled = new int[512];
        System.arraycopy(p, 0, doubled, 0, 256);
        System.arraycopy(p, 0, doubled, 256, 256);
        return doubled;
    }

    /** Ken Perlin's improved fade: 6t⁵ − 15t⁴ + 10t³. */
    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    /** Standard 12 edge gradients of unit cube. */
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /**
     * Improved 3D Perlin noise. Output range approximately [−1, 1].
     */
    public static double perlin(long seed, double x, double y, double z) {
        int[] p = perm(seed);

        int xi = (int) Math.floor(x) & 255;
        int yi = (int) Math.floor(y) & 255;
        int zi = (int) Math.floor(z) & 255;

        double xf = x - Math.floor(x);
        double yf = y - Math.floor(y);
        double zf = z - Math.floor(z);

        double u = fade(xf);
        double v = fade(yf);
        double w = fade(zf);

        int A  = p[xi]     + yi;
        int AA = p[A]      + zi;
        int AB = p[A + 1]  + zi;
        int B  = p[xi + 1] + yi;
        int BA = p[B]      + zi;
        int BB = p[B + 1]  + zi;

        return lerp(w,
            lerp(v,
                lerp(u, grad(p[AA],     xf,       yf,       zf),
                        grad(p[BA],     xf - 1,   yf,       zf)),
                lerp(u, grad(p[AB],     xf,       yf - 1,   zf),
                        grad(p[BB],     xf - 1,   yf - 1,   zf))),
            lerp(v,
                lerp(u, grad(p[AA + 1], xf,       yf,       zf - 1),
                        grad(p[BA + 1], xf - 1,   yf,       zf - 1)),
                lerp(u, grad(p[AB + 1], xf,       yf - 1,   zf - 1),
                        grad(p[BB + 1], xf - 1,   yf - 1,   zf - 1))));
    }

    /**
     * Fractional Brownian Motion: sum of {@code octaves} Perlin layers,
     * each at double frequency and {@code persistence} of the previous amplitude.
     * <p>
     * Result is normalized so its range stays approximately [−1, 1].
     */
    public static double octavePerlin(long seed, int octaves, double persistence,
                                      double lacunarity, double x, double y, double z) {
        double total = 0;
        double freq = 1;
        double amp = 1;
        double maxAmp = 0;
        long octaveSeed = seed;
        for (int i = 0; i < octaves; i++) {
            total += perlin(octaveSeed, x * freq, y * freq, z * freq) * amp;
            maxAmp += amp;
            amp *= persistence;
            freq *= lacunarity;
            octaveSeed = octaveSeed * 6364136223846793005L + 1442695040888963407L; // LCG step
        }
        return total / maxAmp;
    }
}
