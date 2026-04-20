package dev.xssmusashi.atlas.core.dfc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DfcLoaderTest {

    private static DfcNode parse(String json) {
        return DfcLoader.load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parsesConstantFromMinecraftJsonForm() {
        DfcNode node = parse("""
            { "type": "minecraft:constant", "argument": 42.5 }
            """);
        assertThat(node).isEqualTo(new DfcNode.Constant(42.5));
    }

    @Test
    void parsesConstantFromShortNumberForm() {
        assertThat(parse("3.14")).isEqualTo(new DfcNode.Constant(3.14));
    }

    @Test
    void parsesPositionalNodes() {
        assertThat(parse("{\"type\":\"minecraft:x_pos\"}")).isEqualTo(new DfcNode.XPos());
        assertThat(parse("{\"type\":\"minecraft:y_pos\"}")).isEqualTo(new DfcNode.YPos());
        assertThat(parse("{\"type\":\"minecraft:z_pos\"}")).isEqualTo(new DfcNode.ZPos());
    }

    @Test
    void parsesAdd() {
        DfcNode node = parse("""
            { "type": "minecraft:add", "argument1": 1.0, "argument2": 2.0 }
            """);
        assertThat(node).isEqualTo(new DfcNode.Add(new DfcNode.Constant(1.0), new DfcNode.Constant(2.0)));
    }

    @Test
    void parsesNestedTree() {
        DfcNode node = parse("""
            {
              "type": "minecraft:mul",
              "argument1": { "type": "minecraft:x_pos" },
              "argument2": {
                "type": "minecraft:add",
                "argument1": { "type": "minecraft:y_pos" },
                "argument2": 0.5
              }
            }
            """);
        DfcNode expected = new DfcNode.Mul(
            new DfcNode.XPos(),
            new DfcNode.Add(new DfcNode.YPos(), new DfcNode.Constant(0.5))
        );
        assertThat(node).isEqualTo(expected);
    }

    @Test
    void parsesClamp() {
        DfcNode node = parse("""
            {
              "type": "minecraft:clamp",
              "input": { "type": "minecraft:x_pos" },
              "min": -1.0,
              "max": 1.0
            }
            """);
        assertThat(node).isEqualTo(new DfcNode.Clamp(new DfcNode.XPos(), -1.0, 1.0));
    }

    @Test
    void parsesMinMaxAbsNegate() {
        assertThat(parse("{\"type\":\"minecraft:min\",\"argument1\":1,\"argument2\":2}"))
            .isEqualTo(new DfcNode.Min(new DfcNode.Constant(1), new DfcNode.Constant(2)));
        assertThat(parse("{\"type\":\"minecraft:max\",\"argument1\":3,\"argument2\":4}"))
            .isEqualTo(new DfcNode.Max(new DfcNode.Constant(3), new DfcNode.Constant(4)));
        assertThat(parse("{\"type\":\"minecraft:abs\",\"argument\":-5}"))
            .isEqualTo(new DfcNode.Abs(new DfcNode.Constant(-5)));
        assertThat(parse("{\"type\":\"atlas:negate\",\"argument\":7}"))
            .isEqualTo(new DfcNode.Negate(new DfcNode.Constant(7)));
    }

    @Test
    void throwsOnUnknownType() {
        assertThatThrownBy(() -> parse("{\"type\":\"minecraft:unknown_xyz\"}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minecraft:unknown_xyz");
    }
}
