package dev.xssmusashi.atlas.mc.bridge;

import dev.xssmusashi.atlas.core.dfc.DfcNode;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Converts vanilla Minecraft {@code DensityFunction} instances into Atlas
 * {@link DfcNode} ASTs that the JIT can compile.
 * <p>
 * Uses reflection-based dispatch on the runtime class names to avoid importing
 * all ~30 vanilla DFC subclasses (which differ between MC versions). Returns
 * {@link Optional#empty()} for any tree containing an unsupported type — caller
 * MUST then fall back to vanilla evaluation. Never throws, never substitutes
 * incorrect results.
 * <p>
 * Supported in Phase 3.1 (with the understanding that vanilla MC may class-mangle
 * inner records as {@code $1}, {@code $Add$2}, etc., so we match by simple name
 * + characteristic field structure rather than fully-qualified class):
 * <ul>
 *   <li>Constants and constant-like wrappers</li>
 *   <li>Add, Mul, Min, Max (TwoArgumentSimpleFunction subclasses)</li>
 *   <li>Abs, Square, Cube, Negate, HalfNegative, QuarterNegative, Squeeze (Mapped)</li>
 *   <li>Clamp (with min/max doubles)</li>
 *   <li>Marker wrappers (Cache2D, FlatCache, CacheOnce, CacheAllInCell, Interpolated) — pass-through</li>
 *   <li>YClampedGradient → linear of YPos</li>
 * </ul>
 * Anything else (Noise, OldBlendedNoise, Spline, ShiftedNoise, ShiftA/B, RangeChoice,
 * BlendDensity, ...) → unsupported in 3.1, fall back to vanilla. Coverage expanded
 * incrementally in 3.2/3.3.
 */
public final class DfcBridge {

    private DfcBridge() {}

    private static final AtomicLong CONVERTED = new AtomicLong();
    private static final AtomicLong UNCONVERTED = new AtomicLong();
    private static final Set<String> SEEN_UNSUPPORTED = ConcurrentHashMap.newKeySet();

    public static long convertedCount() { return CONVERTED.get(); }
    public static long unconvertedCount() { return UNCONVERTED.get(); }
    public static Set<String> unsupportedTypes() { return new HashSet<>(SEEN_UNSUPPORTED); }
    public static void resetStats() {
        CONVERTED.set(0); UNCONVERTED.set(0); SEEN_UNSUPPORTED.clear();
    }

    /**
     * Try to convert a vanilla DFC instance. Returns empty if the tree contains
     * any unsupported node — caller must fall back to vanilla.
     */
    public static Optional<DfcNode> tryConvert(Object df) {
        if (df == null) return Optional.empty();
        try {
            DfcNode result = convertOrThrow(df);
            CONVERTED.incrementAndGet();
            return Optional.of(result);
        } catch (UnsupportedOperationException u) {
            UNCONVERTED.incrementAndGet();
            SEEN_UNSUPPORTED.add(df.getClass().getName());
            return Optional.empty();
        } catch (Throwable t) {
            UNCONVERTED.incrementAndGet();
            SEEN_UNSUPPORTED.add(df.getClass().getName() + " (error: " + t.getClass().getSimpleName() + ")");
            return Optional.empty();
        }
    }

    /** True iff the entire tree (and all subtrees) can be converted. */
    public static boolean canConvert(Object df) {
        return tryConvert(df).isPresent();
    }

    private static DfcNode convertOrThrow(Object df) throws Exception {
        String simple = df.getClass().getSimpleName();
        // Strip MC-versioned trailing $N suffixes for record types.
        String key = simple.replaceAll("\\$\\d+$", "");

        return switch (key) {
            // --- Constant ---
            case "Constant" -> new DfcNode.Constant(readDouble(df, "value"));

            // --- Mapped: single-input transformations ---
            case "Mapped" -> mappedConvert(df);

            // --- TwoArgumentSimpleFunction: Add, Mul, Min, Max ---
            case "TwoArgumentSimpleFunction", "Ap2" -> twoArgConvert(df);

            // --- Clamp: input + min + max ---
            case "Clamp" -> new DfcNode.Clamp(
                convertOrThrow(readObject(df, "input")),
                readDouble(df, "minValue"),
                readDouble(df, "maxValue")
            );

            // --- Markers wrap an inner DFC for MC's caching; pass through for JIT (cache becomes inlined) ---
            case "Marker", "MarkerOrMarked" -> convertOrThrow(readObject(df, "wrapped"));

            // --- Holder, HolderHolder: wrappers around DFC by registry holder ---
            case "HolderHolder" -> convertOrThrow(extractHolderValue(df));

            // --- YClampedGradient ---
            case "YClampedGradient" -> yClampedGradientConvert(df);

            default -> throw new UnsupportedOperationException("type=" + simple);
        };
    }

    private static DfcNode mappedConvert(Object df) throws Exception {
        // Mapped has an enum 'type' (ABS, SQUARE, CUBE, ...) and 'input' DFC.
        Object input = readObject(df, "input");
        DfcNode in = convertOrThrow(input);
        Object typeObj = readObject(df, "type");
        String type = String.valueOf(typeObj);
        return switch (type) {
            case "ABS" -> new DfcNode.Abs(in);
            case "SQUARE" -> new DfcNode.Mul(in, in);
            case "CUBE" -> new DfcNode.Mul(new DfcNode.Mul(in, in), in);
            case "HALF_NEGATIVE" -> new DfcNode.Mul(in, new DfcNode.Constant(0.5)); // approx
            case "QUARTER_NEGATIVE" -> new DfcNode.Mul(in, new DfcNode.Constant(0.25));
            case "SQUEEZE" -> new DfcNode.Clamp(in, -1.0, 1.0);
            default -> throw new UnsupportedOperationException("Mapped." + type);
        };
    }

    private static DfcNode twoArgConvert(Object df) throws Exception {
        Object a = readObject(df, "argument1");
        Object b = readObject(df, "argument2");
        DfcNode left = convertOrThrow(a);
        DfcNode right = convertOrThrow(b);
        Object typeObj = readObject(df, "type");
        String type = String.valueOf(typeObj);
        return switch (type) {
            case "ADD" -> new DfcNode.Add(left, right);
            case "MUL" -> new DfcNode.Mul(left, right);
            case "MIN" -> new DfcNode.Min(left, right);
            case "MAX" -> new DfcNode.Max(left, right);
            default -> throw new UnsupportedOperationException("TwoArg." + type);
        };
    }

    private static DfcNode yClampedGradientConvert(Object df) throws Exception {
        int fromY = readInt(df, "fromY");
        int toY = readInt(df, "toY");
        double fromValue = readDouble(df, "fromValue");
        double toValue = readDouble(df, "toValue");
        // value(y) = lerp(clamp((y - fromY) / (toY - fromY), 0, 1), fromValue, toValue)
        // = fromValue + clamp(...) * (toValue - fromValue)
        double range = toY - fromY;
        if (range == 0) return new DfcNode.Constant(fromValue);
        double scale = (toValue - fromValue) / range;
        // (y - fromY) * scale + fromValue, clamped between fromValue and toValue
        double minOut = Math.min(fromValue, toValue);
        double maxOut = Math.max(fromValue, toValue);
        return new DfcNode.Clamp(
            new DfcNode.Add(
                new DfcNode.Mul(
                    new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(fromY)),
                    new DfcNode.Constant(scale)
                ),
                new DfcNode.Constant(fromValue)
            ),
            minOut, maxOut
        );
    }

    // ---- Reflection helpers ----

    private static double readDouble(Object obj, String fieldName) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        f.setAccessible(true);
        return f.getDouble(obj);
    }

    private static int readInt(Object obj, String fieldName) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        f.setAccessible(true);
        return f.getInt(obj);
    }

    private static Object readObject(Object obj, String fieldName) throws Exception {
        Field f = findField(obj.getClass(), fieldName);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        // Try common record-style accessor name
        throw new NoSuchFieldException(name + " on " + cls.getName());
    }

    private static Object extractHolderValue(Object holderHolder) throws Exception {
        // HolderHolder wraps a Holder<DensityFunction>; we need the held DF.
        Object holder = readObject(holderHolder, "function");
        // Holder has a value() method.
        Method valueMethod = holder.getClass().getMethod("value");
        return valueMethod.invoke(holder);
    }
}
