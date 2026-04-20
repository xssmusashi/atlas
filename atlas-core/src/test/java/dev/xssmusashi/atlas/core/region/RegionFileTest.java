package dev.xssmusashi.atlas.core.region;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.tile.NoiseStage;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegionFileTest {

    private static Tile generateTile(TileCoord coord, long seed) {
        DfcNode tree = new DfcNode.OctavePerlin(
            0L, 3, 0.5, 2.0, 0.02,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        CompiledSampler s = JitCompiler.compile(tree);
        Tile t = new Tile(coord, seed);
        new NoiseStage(s).run(t);
        return t;
    }

    @Test
    void open_createsEmptyFileWithValidHeader(@TempDir Path dir) throws IOException {
        try (RegionFile rf = RegionFile.open(dir, new RegionCoord(0, 0))) {
            assertThat(rf.path()).exists();
            assertThat(rf.has(new TileCoord(0, 0))).isFalse();
        }
    }

    @Test
    void writeTile_thenReadTile_roundTrips(@TempDir Path dir) throws IOException {
        TileCoord tc = new TileCoord(2, 3);
        try (Tile original = generateTile(tc, 555L);
             RegionFile rf = RegionFile.open(dir, RegionCoord.fromTile(tc))) {
            rf.writeTile(original);
            assertThat(rf.has(tc)).isTrue();

            try (Tile restored = rf.readTile(tc, 555L).orElseThrow()) {
                long mismatch = original.noiseField().mismatch(restored.noiseField());
                assertThat(mismatch).as("noise field byte mismatch").isEqualTo(-1);
            }
        }
    }

    @Test
    void writeMultipleTiles_persistAcrossReopen(@TempDir Path dir) throws IOException {
        RegionCoord rc = new RegionCoord(0, 0);
        TileCoord[] coords = {
            new TileCoord(0, 0), new TileCoord(5, 5), new TileCoord(15, 15)
        };

        try (RegionFile rf = RegionFile.open(dir, rc)) {
            for (TileCoord tc : coords) {
                try (Tile t = generateTile(tc, 1L)) {
                    rf.writeTile(t);
                }
            }
        }

        try (RegionFile rf = RegionFile.open(dir, rc)) {
            for (TileCoord tc : coords) {
                assertThat(rf.has(tc)).as(tc + " present after reopen").isTrue();
                try (Tile t = rf.readTile(tc, 1L).orElseThrow()) {
                    assertThat(t.noiseField().byteSize())
                        .isEqualTo((long) Tile.WORLD_HEIGHT * Tile.TILE_AREA * Double.BYTES);
                }
            }
        }
    }

    @Test
    void readTile_returnsEmptyForUnwrittenSlot(@TempDir Path dir) throws IOException {
        try (RegionFile rf = RegionFile.open(dir, new RegionCoord(0, 0))) {
            Optional<Tile> result = rf.readTile(new TileCoord(7, 7), 0L);
            assertThat(result).isEmpty();
        }
    }

    @Test
    void writeTile_rejectsTilesNotInThisRegion(@TempDir Path dir) throws IOException {
        try (RegionFile rf = RegionFile.open(dir, new RegionCoord(0, 0));
             Tile foreign = generateTile(new TileCoord(20, 20), 0L)) {
            assertThatThrownBy(() -> rf.writeTile(foreign))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to region");
        }
    }

    @Test
    void writeTileAtomicFile_renameAfterFsync(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("standalone.atr");
        try (Tile t = generateTile(new TileCoord(0, 0), 0L)) {
            RegionFile.writeTileAtomicFile(file, t);
        }
        assertThat(file).exists();
        assertThat(dir.resolve("standalone.atr.tmp")).doesNotExist();
    }

    @Test
    void overwriteTile_replacesContent(@TempDir Path dir) throws IOException {
        TileCoord tc = new TileCoord(0, 0);
        try (RegionFile rf = RegionFile.open(dir, RegionCoord.fromTile(tc))) {
            try (Tile t1 = generateTile(tc, 100L)) { rf.writeTile(t1); }
            try (Tile t2 = generateTile(tc, 999L)) { rf.writeTile(t2); }

            try (Tile restored = rf.readTile(tc, 0L).orElseThrow();
                 Tile expected = generateTile(tc, 999L)) {
                long mismatch = expected.noiseField().mismatch(restored.noiseField());
                assertThat(mismatch).isEqualTo(-1);
            }
        }
    }
}
