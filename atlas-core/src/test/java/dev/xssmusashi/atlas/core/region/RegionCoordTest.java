package dev.xssmusashi.atlas.core.region;

import dev.xssmusashi.atlas.core.tile.TileCoord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegionCoordTest {

    @Test
    void fromTile_groupsBy16x16() {
        assertThat(RegionCoord.fromTile(new TileCoord(0, 0))).isEqualTo(new RegionCoord(0, 0));
        assertThat(RegionCoord.fromTile(new TileCoord(15, 15))).isEqualTo(new RegionCoord(0, 0));
        assertThat(RegionCoord.fromTile(new TileCoord(16, 0))).isEqualTo(new RegionCoord(1, 0));
        assertThat(RegionCoord.fromTile(new TileCoord(-1, -1))).isEqualTo(new RegionCoord(-1, -1));
        assertThat(RegionCoord.fromTile(new TileCoord(-16, -16))).isEqualTo(new RegionCoord(-1, -1));
        assertThat(RegionCoord.fromTile(new TileCoord(-17, -17))).isEqualTo(new RegionCoord(-2, -2));
    }

    @Test
    void slotInRegion_isZeroToTwoFiftyFive() {
        assertThat(RegionCoord.slotInRegion(new TileCoord(0, 0))).isEqualTo(0);
        assertThat(RegionCoord.slotInRegion(new TileCoord(15, 0))).isEqualTo(15);
        assertThat(RegionCoord.slotInRegion(new TileCoord(0, 1))).isEqualTo(16);
        assertThat(RegionCoord.slotInRegion(new TileCoord(15, 15))).isEqualTo(255);
        // Negative tiles wrap into positive slot indices.
        assertThat(RegionCoord.slotInRegion(new TileCoord(-1, -1))).isEqualTo(15 * 16 + 15);
    }

    @Test
    void fileName_matchesExpectedLayout() {
        assertThat(new RegionCoord(0, 0).fileName()).isEqualTo("r.0.0.atr");
        assertThat(new RegionCoord(3, -2).fileName()).isEqualTo("r.3.-2.atr");
    }
}
