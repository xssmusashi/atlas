package dev.xssmusashi.atlas.bench;

import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.region.RegionCoord;
import dev.xssmusashi.atlas.core.region.RegionFile;
import dev.xssmusashi.atlas.core.region.TileSerializer;
import dev.xssmusashi.atlas.core.tile.NoiseStage;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Region I/O throughput: serialize, write, read, deserialize a noise tile (~48 MB raw).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 2, time = 3)
@Measurement(iterations = 3, time = 5)
@Fork(1)
public class RegionIoBench {

    private Tile sourceTile;
    private byte[] precompressedPayload;
    private int uncompressedSize;
    private Path tempDir;
    private RegionFile regionFile;
    private int writeCounter;

    @Setup(Level.Trial)
    public void setup() throws IOException {
        DfcNode tree = new DfcNode.OctavePerlin(
            0L, 6, 0.5, 2.0, 0.01,
            new DfcNode.XPos(), new DfcNode.YPos(), new DfcNode.ZPos()
        );
        CompiledSampler s = JitCompiler.compile(tree);
        sourceTile = new Tile(new TileCoord(0, 0), 42L);
        new NoiseStage(s).run(sourceTile);
        precompressedPayload = TileSerializer.serialize(sourceTile);
        uncompressedSize = 6 + (int) sourceTile.noiseField().byteSize();

        tempDir = Files.createTempDirectory("atlas-bench-");
        regionFile = RegionFile.open(tempDir, new RegionCoord(0, 0));
    }

    @TearDown(Level.Trial)
    public void teardown() throws IOException {
        regionFile.close();
        sourceTile.close();
        try (var stream = Files.walk(tempDir)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    @Benchmark
    public void serialize_only(Blackhole bh) {
        bh.consume(TileSerializer.serialize(sourceTile));
    }

    @Benchmark
    public void deserialize_only(Blackhole bh) {
        try (Tile out = new Tile(new TileCoord(0, 0), 0L)) {
            TileSerializer.deserializeInto(precompressedPayload, uncompressedSize, out);
            bh.consume(out);
        }
    }

    @Benchmark
    public void writeTile_includesFsync(Blackhole bh) throws IOException {
        // Cycle through slots so the bench doesn't always hit the same file offset.
        int slotX = writeCounter & 15;
        int slotZ = (writeCounter >> 4) & 15;
        writeCounter++;
        // Reuse sourceTile but write under different coord — needs region match, so we
        // hijack the coord via a temporary tile holding a shared noise field is non-trivial;
        // simpler: just write the source under coord (0,0) repeatedly to measure I/O cost.
        regionFile.writeTile(sourceTile);
        bh.consume(slotX + slotZ);
    }

    @Benchmark
    public void readTile_roundtrip(Blackhole bh) throws IOException {
        // Pre-populate slot (0,0) once at trial setup via writeTile, then keep reading.
        // We piggyback on whatever was written by writeTile_includesFsync benchmarks.
        regionFile.writeTile(sourceTile); // ensure something is there
        try (Tile t = regionFile.readTile(new TileCoord(0, 0), 0L).orElseThrow()) {
            bh.consume(t);
        }
    }
}
