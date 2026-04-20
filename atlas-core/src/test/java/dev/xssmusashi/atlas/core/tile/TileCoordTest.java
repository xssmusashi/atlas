package dev.xssmusashi.atlas.core.tile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TileCoordTest {

    @Test
    void fromChunk_groupsBy8x8() {
        assertThat(TileCoord.fromChunk(0, 0)).isEqualTo(new TileCoord(0, 0));
        assertThat(TileCoord.fromChunk(7, 7)).isEqualTo(new TileCoord(0, 0));
        assertThat(TileCoord.fromChunk(8, 0)).isEqualTo(new TileCoord(1, 0));
        assertThat(TileCoord.fromChunk(0, 8)).isEqualTo(new TileCoord(0, 1));
        assertThat(TileCoord.fromChunk(15, 15)).isEqualTo(new TileCoord(1, 1));
    }

    @Test
    void fromChunk_handlesNegativeCoordinatesCorrectly() {
        assertThat(TileCoord.fromChunk(-1, -1)).isEqualTo(new TileCoord(-1, -1));
        assertThat(TileCoord.fromChunk(-8, -8)).isEqualTo(new TileCoord(-1, -1));
        assertThat(TileCoord.fromChunk(-9, -9)).isEqualTo(new TileCoord(-2, -2));
    }

    @Test
    void fromBlock_groupsBy128x128() {
        assertThat(TileCoord.fromBlock(0, 0)).isEqualTo(new TileCoord(0, 0));
        assertThat(TileCoord.fromBlock(127, 127)).isEqualTo(new TileCoord(0, 0));
        assertThat(TileCoord.fromBlock(128, 0)).isEqualTo(new TileCoord(1, 0));
        assertThat(TileCoord.fromBlock(-1, -1)).isEqualTo(new TileCoord(-1, -1));
    }

    @Test
    void baseBlockCoords() {
        TileCoord t = new TileCoord(3, -2);
        assertThat(t.baseBlockX()).isEqualTo(3 * 128);
        assertThat(t.baseBlockZ()).isEqualTo(-2 * 128);
    }
}
