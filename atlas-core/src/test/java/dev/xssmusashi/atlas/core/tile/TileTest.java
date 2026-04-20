package dev.xssmusashi.atlas.core.tile;

import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TileTest {

    @Test
    void newTile_isInNewState() {
        try (Tile t = new Tile(new TileCoord(0, 0), 0L)) {
            assertThat(t.state()).isEqualTo(TileState.NEW);
        }
    }

    @Test
    void noiseField_unallocated_throws() {
        try (Tile t = new Tile(new TileCoord(0, 0), 0L)) {
            assertThatThrownBy(t::noiseField).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void noiseField_canBeAllocatedAndAccessed() {
        try (Tile t = new Tile(new TileCoord(0, 0), 0L)) {
            MemorySegment seg = t.ensureNoiseField();
            // Buffer must hold full tile volume.
            long expectedBytes = (long) Tile.WORLD_HEIGHT * Tile.TILE_AREA * Double.BYTES;
            assertThat(seg.byteSize()).isEqualTo(expectedBytes);

            Tile.writeNoise(seg, 7, 100, 11, 3.14);
            assertThat(Tile.readNoise(seg, 7, 100, 11)).isEqualTo(3.14);

            // Distinct cells must not alias.
            Tile.writeNoise(seg, 8, 100, 11, 2.71);
            assertThat(Tile.readNoise(seg, 7, 100, 11)).isEqualTo(3.14);
            assertThat(Tile.readNoise(seg, 8, 100, 11)).isEqualTo(2.71);
        }
    }

    @Test
    void noiseIndex_layoutIsYMajor() {
        // Adjacent X cells in the same row are adjacent in the linear layout.
        int idx0 = Tile.noiseIndex(0, 100, 0);
        int idx1 = Tile.noiseIndex(1, 100, 0);
        assertThat(idx1 - idx0).isEqualTo(1);

        // Adjacent Z cells differ by TILE_BLOCKS.
        int idxZ0 = Tile.noiseIndex(0, 100, 0);
        int idxZ1 = Tile.noiseIndex(0, 100, 1);
        assertThat(idxZ1 - idxZ0).isEqualTo(Tile.TILE_BLOCKS);

        // Adjacent Y planes differ by TILE_AREA.
        int idxY0 = Tile.noiseIndex(0, 100, 0);
        int idxY1 = Tile.noiseIndex(0, 101, 0);
        assertThat(idxY1 - idxY0).isEqualTo(Tile.TILE_AREA);
    }

    @Test
    void transitionTo_atomicAllowsOneWinner() {
        try (Tile t = new Tile(new TileCoord(0, 0), 0L)) {
            assertThat(t.transitionTo(TileState.NEW, TileState.NOISE)).isTrue();
            assertThat(t.state()).isEqualTo(TileState.NOISE);
            // Second attempt fails — already advanced.
            assertThat(t.transitionTo(TileState.NEW, TileState.NOISE)).isFalse();
        }
    }
}
