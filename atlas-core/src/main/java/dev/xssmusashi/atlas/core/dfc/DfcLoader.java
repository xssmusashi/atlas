package dev.xssmusashi.atlas.core.dfc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Parses Minecraft DensityFunction JSON into {@link DfcNode} AST.
 * <p>
 * Supported node types (Phase 1, sub-plans 1-2):
 * <ul>
 *   <li>{@code minecraft:constant} (and short number form)</li>
 *   <li>{@code minecraft:x_pos}, {@code minecraft:y_clamped_gradient} (mapped to YPos), {@code minecraft:z_pos}</li>
 *   <li>{@code minecraft:add}, {@code minecraft:mul}, {@code minecraft:min}, {@code minecraft:max}</li>
 *   <li>{@code minecraft:abs}, {@code minecraft:clamp}</li>
 * </ul>
 * Plus Atlas-specific aliases: {@code atlas:x_pos}, {@code atlas:y_pos}, {@code atlas:z_pos},
 * {@code atlas:sub}, {@code atlas:negate}.
 * <p>
 * Unknown types throw {@link IllegalArgumentException}.
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
        if (!el.isJsonObject()) {
            throw new IllegalArgumentException("Cannot parse DFC element: " + el);
        }
        JsonObject obj = el.getAsJsonObject();
        String type = obj.get("type").getAsString();
        return switch (type) {
            case "minecraft:constant" -> new DfcNode.Constant(obj.get("argument").getAsDouble());

            case "minecraft:x_pos", "atlas:x_pos" -> new DfcNode.XPos();
            case "minecraft:y_pos", "atlas:y_pos" -> new DfcNode.YPos();
            case "minecraft:z_pos", "atlas:z_pos" -> new DfcNode.ZPos();

            case "minecraft:add" -> new DfcNode.Add(parse(obj.get("argument1")), parse(obj.get("argument2")));
            case "atlas:sub"     -> new DfcNode.Sub(parse(obj.get("argument1")), parse(obj.get("argument2")));
            case "minecraft:mul" -> new DfcNode.Mul(parse(obj.get("argument1")), parse(obj.get("argument2")));
            case "atlas:negate"  -> new DfcNode.Negate(parse(obj.get("argument")));
            case "minecraft:abs" -> new DfcNode.Abs(parse(obj.get("argument")));

            case "minecraft:min" -> new DfcNode.Min(parse(obj.get("argument1")), parse(obj.get("argument2")));
            case "minecraft:max" -> new DfcNode.Max(parse(obj.get("argument1")), parse(obj.get("argument2")));
            case "minecraft:clamp" -> new DfcNode.Clamp(
                parse(obj.get("input")),
                obj.get("min").getAsDouble(),
                obj.get("max").getAsDouble()
            );

            default -> throw new IllegalArgumentException(
                "Unsupported DFC node type in Phase 1: " + type
            );
        };
    }
}
