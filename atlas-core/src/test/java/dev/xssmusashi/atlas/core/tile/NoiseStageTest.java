package dev.xssmusashi.atlas.core.tile;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.NoiseRuntime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class NoiseStageTest {

    @Test
    void run_populatesEntireNoiseField() {
        DfcNode tree = new DfcNode.Add(
            new DfcNode.Mul(new DfcNode.XPos(), new DfcNode.Constant(0.001)),
            new DfcNode.Mul(new DfcNode.YPos(), new DfcNode.Constant(0.01))
        );
        CompiledSampler sampler = JitCompiler.compile(tree);
        NoiseStage stage = new NoiseStage(sampler);

        try (Tile tile = new Tile(new TileCoord(0, 0), 42L)) {
            stage.run(tile);
            assertThat(tile.state()).isEqualTo(TileState.NOISE);

            // Spot check a handful of cells against direct sampler invocation.
            var seg = tile.noiseField();
            for (int dx : new int[]{0, 7, 64, 127}) {
                for (int y : new int[]{-64, 0, 100, 319}) {
                    for (int dz : new int[]{0, 33, 100, 127}) {
                        double expected = sampler.sample(dx, y, dz, 42L);
                        double actual = Tile.readNoise(seg, dx, y, dz);
                        assertThat(actual)
                            .as("(%d, %d, %d)", dx, y, dz)
                            .isCloseTo(expected, within(1e-15));
                    }
                }
            }
        }
    }

    @Test
    void run_appliesTileBaseOffsetCorrectly() {
        DfcNode tree = new DfcNode.XPos();
        CompiledSampler sampler = JitCompiler.compile(tree);
        NoiseStage stage = new NoiseStage(sampler);

        try (Tile tile = new Tile(new TileCoord(3, -1), 0L)) {
            stage.run(tile);
            // Local cell (5, 100, 0) maps to world (3*128 + 5, 100, -1*128 + 0) = (389, 100, -128)
            double cell = Tile.readNoise(tile.noiseField(), 5, 100, 0);
            assertThat(cell).isEqualTo(389.0);
        }
    }

    @Test
    void run_isIdempotent_doesNotReexecute() {
        // Tracking sampler that counts invocations.
        int[] calls = {0};
        CompiledSampler tracking = (x, y, z, seed) -> {
            calls[0]++;
            return 0.0;
        };
        NoiseStage stage = new NoiseStage(tracking);

        try (Tile tile = new Tile(new TileCoord(0, 0), 0L)) {
            stage.run(tile);
            int firstRunCalls = calls[0];
            assertThat(firstRunCalls).isGreaterThan(0);
            stage.run(tile); // second run should no-op
            assertThat(calls[0]).isEqualTo(firstRunCalls);
        }
    }

    @Test
    void noiseField_correctForRealPerlinTree() {
        DfcNode tree = new DfcNode.OctavePerlin(
            0L, 4, 0.5, 2.0, 0.01,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        CompiledSampler sampler = JitCompiler.compile(tree);
        NoiseStage stage = new NoiseStage(sampler);

        try (Tile tile = new Tile(new TileCoord(2, 2), 0xCAFE_BABEL)) {
            stage.run(tile);
            // Verify a sample matches direct NoiseRuntime call (no caching trickery).
            double expected = NoiseRuntime.octavePerlin(
                0xCAFE_BABEL, 4, 0.5, 2.0,
                (2 * 128 + 50) * 0.01, 100 * 0.01, (2 * 128 + 70) * 0.01
            );
            double actual = Tile.readNoise(tile.noiseField(), 50, 100, 70);
            assertThat(actual).isEqualTo(expected);
        }
    }
}
