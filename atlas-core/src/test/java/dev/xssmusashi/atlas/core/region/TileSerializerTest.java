package dev.xssmusashi.atlas.core.region;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.tile.NoiseStage;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import dev.xssmusashi.atlas.core.tile.TileState;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;

class TileSerializerTest {

    private static Tile generateTile(TileCoord coord, long seed) {
        DfcNode tree = new DfcNode.OctavePerlin(
            0L, 4, 0.5, 2.0, 0.01,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        CompiledSampler s = JitCompiler.compile(tree);
        Tile t = new Tile(coord, seed);
        new NoiseStage(s).run(t);
        return t;
    }

    @Test
    void serialize_thenDeserialize_yieldsByteIdenticalNoiseField() {
        try (Tile original = generateTile(new TileCoord(2, 3), 7777L)) {
            byte[] payload = TileSerializer.serialize(original);
            assertThat(payload).isNotEmpty();

            try (Tile restored = new Tile(original.coord, original.seed)) {
                int origBytes = (int) original.noiseField().byteSize();
                TileSerializer.deserializeInto(payload, 6 + origBytes, restored);

                // Byte-equal across the whole noise field.
                var origSeg = original.noiseField();
                var restSeg = restored.noiseField();
                assertThat(restSeg.byteSize()).isEqualTo(origSeg.byteSize());
                long mismatchAt = origSeg.mismatch(restSeg);
                assertThat(mismatchAt).as("byte mismatch position").isEqualTo(-1);
            }
        }
    }

    @Test
    void compressionRatio_atLeastBreaksEven() {
        try (Tile tile = generateTile(new TileCoord(0, 0), 0L)) {
            int uncompressedNoise = (int) tile.noiseField().byteSize();
            byte[] payload = TileSerializer.serialize(tile);
            // Raw IEEE 754 doubles have high-entropy mantissas; smooth Perlin noise still
            // gives only modest compression ratios with general-purpose zstd. We assert no
            // blow-up. Real compression wins land in sub-plan 6 with quantization (half
            // precision storage) and a trained zstd dictionary.
            double ratio = (double) (6 + uncompressedNoise) / payload.length;
            assertThat(ratio).as("compression ratio").isGreaterThanOrEqualTo(1.0);
            System.out.printf("[bench] zstd-3 ratio on Perlin noise tile: %.2fx (%d -> %d bytes)%n",
                ratio, 6 + uncompressedNoise, payload.length);
        }
    }

    @Test
    void deserialize_intoFreshTile_preservesSpecificCells() {
        try (Tile original = generateTile(new TileCoord(0, 0), 42L)) {
            byte[] payload = TileSerializer.serialize(original);

            try (Tile restored = new Tile(new TileCoord(0, 0), 42L)) {
                TileSerializer.deserializeInto(payload, 6 + (int) original.noiseField().byteSize(), restored);

                for (int dx : new int[]{0, 17, 64, 127}) {
                    for (int y : new int[]{-64, 50, 200, 319}) {
                        for (int dz : new int[]{0, 33, 90, 127}) {
                            double a = Tile.readNoise(original.noiseField(), dx, y, dz);
                            double b = Tile.readNoise(restored.noiseField(), dx, y, dz);
                            assertThat(Double.doubleToRawLongBits(a))
                                .as("(%d, %d, %d)", dx, y, dz)
                                .isEqualTo(Double.doubleToRawLongBits(b));
                        }
                    }
                }
            }
        }
    }
}
