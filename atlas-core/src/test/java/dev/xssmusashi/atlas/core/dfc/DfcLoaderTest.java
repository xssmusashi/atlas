package dev.xssmusashi.atlas.core.dfc;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DfcLoaderTest {

    @Test
    void parsesConstantFromMinecraftJsonForm() {
        String json = """
            { "type": "minecraft:constant", "argument": 42.5 }
            """;
        DfcNode node = DfcLoader.load(new ByteArrayInputStream(json.getBytes()));
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
        assertThat(((DfcNode.Constant) node).value()).isEqualTo(42.5);
    }

    @Test
    void parsesConstantFromShortNumberForm() {
        String json = "3.14";
        DfcNode node = DfcLoader.load(new ByteArrayInputStream(json.getBytes()));
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
        assertThat(((DfcNode.Constant) node).value()).isEqualTo(3.14);
    }

    @Test
    void throwsOnUnknownType() {
        String json = """
            { "type": "minecraft:unknown_xyz" }
            """;
        assertThatThrownBy(() -> DfcLoader.load(new ByteArrayInputStream(json.getBytes())))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minecraft:unknown_xyz");
    }
}
