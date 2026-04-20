package dev.xssmusashi.atlas.core.region;

import com.github.luben.zstd.Zstd;
import dev.xssmusashi.atlas.core.tile.Tile;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Serializes a {@link Tile} to a zstd-compressed byte payload and back.
 * <p>
 * Phase 1 only persists the noise field. {@code BIOMES}/{@code BLOCKS} sections
 * are added in sub-plan 6 alongside their data structures.
 * <p>
 * Wire format of the uncompressed payload:
 * <pre>
 *   sectionCount : u8
 *   sectionType  : u8        (= AtrFormat.SECTION_NOISE)
 *   uncompressedSize : u32
 *   payload bytes : raw little-endian doubles, length = WORLD_HEIGHT × TILE_AREA × 8
 * </pre>
 */
public final class TileSerializer {

    private TileSerializer() {}

    /** Compression level used by {@link #serialize(Tile)}. Range 1..22; 3 is a balanced default. */
    public static final int COMPRESSION_LEVEL = 3;

    public static byte[] serialize(Tile tile) {
        MemorySegment noise = tile.noiseField();
        int noiseBytes = (int) noise.byteSize();

        // Header: 1 (sectionCount) + 1 (type) + 4 (size) = 6 bytes prefix
        ByteBuffer raw = ByteBuffer.allocate(6 + noiseBytes).order(ByteOrder.LITTLE_ENDIAN);
        raw.put((byte) 1);                       // sectionCount
        raw.put(AtrFormat.SECTION_NOISE);        // type
        raw.putInt(noiseBytes);                  // uncompressed size
        // Copy the off-heap noise field into the byte buffer.
        for (int i = 0; i < noiseBytes; i++) {
            raw.put(noise.get(ValueLayout.JAVA_BYTE, i));
        }
        raw.flip();
        byte[] uncompressed = raw.array();

        long compressedBound = Zstd.compressBound(uncompressed.length);
        byte[] compressed = new byte[(int) compressedBound];
        long written = Zstd.compressByteArray(
            compressed, 0, compressed.length,
            uncompressed, 0, uncompressed.length,
            COMPRESSION_LEVEL
        );
        if (Zstd.isError(written)) {
            throw new RuntimeException("zstd compress failed: " + Zstd.getErrorName(written));
        }
        byte[] result = new byte[(int) written];
        System.arraycopy(compressed, 0, result, 0, (int) written);
        return result;
    }

    /**
     * Decompress a tile payload back into the supplied destination tile's noise field.
     * The destination tile must be in {@link dev.xssmusashi.atlas.core.tile.TileState#NEW}.
     */
    public static void deserializeInto(byte[] compressed, int uncompressedSize, Tile destination) {
        byte[] uncompressed = new byte[uncompressedSize];
        long decoded = Zstd.decompressByteArray(
            uncompressed, 0, uncompressed.length,
            compressed, 0, compressed.length
        );
        if (Zstd.isError(decoded)) {
            throw new RuntimeException("zstd decompress failed: " + Zstd.getErrorName(decoded));
        }
        if (decoded != uncompressedSize) {
            throw new RuntimeException("decompressed size mismatch: expected "
                + uncompressedSize + " got " + decoded);
        }

        ByteBuffer in = ByteBuffer.wrap(uncompressed).order(ByteOrder.LITTLE_ENDIAN);
        int sectionCount = in.get() & 0xFF;
        for (int s = 0; s < sectionCount; s++) {
            byte type = in.get();
            int size = in.getInt();
            switch (type) {
                case AtrFormat.SECTION_NOISE -> {
                    MemorySegment noise = destination.ensureNoiseField();
                    if (noise.byteSize() != size) {
                        throw new RuntimeException("noise section size mismatch: tile expects "
                            + noise.byteSize() + ", file has " + size);
                    }
                    for (int i = 0; i < size; i++) {
                        noise.set(ValueLayout.JAVA_BYTE, i, in.get());
                    }
                }
                default -> {
                    // Unknown section — skip (forward compatibility).
                    in.position(in.position() + size);
                }
            }
        }
    }
}
