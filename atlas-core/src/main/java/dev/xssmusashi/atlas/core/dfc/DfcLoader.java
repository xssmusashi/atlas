package dev.xssmusashi.atlas.core.dfc;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses Minecraft DensityFunction JSON into {@link DfcNode} AST.
 * <p>
 * Phase 1: only {@code minecraft:constant} type and short number form supported.
 * Other types throw {@link IllegalArgumentException} (sub-plan 2 adds them).
 */
public final class DfcLoader {

    private DfcLoader() {}

    public static DfcNode load(InputStream json) {
        JsonElement el = JsonParser.parseReader(new InputStreamReader(json, StandardCharsets.UTF_8));
        return parse(el);
    }

    private static DfcNode parse(JsonElement el) {
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber()) {
            return new DfcNode.Constant(el.getAsDouble());
        }
        if (el.isJsonObject()) {
            String type = el.getAsJsonObject().get("type").getAsString();
            return switch (type) {
                case "minecraft:constant" -> new DfcNode.Constant(
                    el.getAsJsonObject().get("argument").getAsDouble()
                );
                default -> throw new IllegalArgumentException(
                    "Unsupported DFC node type in Phase 1: " + type
                );
            };
        }
        throw new IllegalArgumentException("Cannot parse DFC element: " + el);
    }
}
