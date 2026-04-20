package dev.xssmusashi.atlas.core.dfc;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DfcNodeTest {

    @Test
    void constantNode_holdsItsValue() {
        DfcNode.Constant c = new DfcNode.Constant(42.5);
        assertThat(c.value()).isEqualTo(42.5);
    }

    @Test
    void constantNode_isInstanceOfDfcNode() {
        DfcNode node = new DfcNode.Constant(0.0);
        assertThat(node).isInstanceOf(DfcNode.Constant.class);
    }

    @Test
    void constantNode_equality() {
        assertThat(new DfcNode.Constant(1.0))
            .isEqualTo(new DfcNode.Constant(1.0))
            .isNotEqualTo(new DfcNode.Constant(2.0));
    }
}
