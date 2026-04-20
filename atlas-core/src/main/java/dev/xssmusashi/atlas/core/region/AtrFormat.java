package dev.xssmusashi.atlas.core.region;

/**
 * On-disk layout constants for the {@code .atr} region file format.
 *
 * <pre>
 * +-----------------------------+
 * | Header (64 bytes)           |
 * |   magic   : "ATR1" (4 B)    |
 * |   version : u32             |
 * |   reserved: 56 B            |
 * +-----------------------------+
 * | Index (256 entries × 16 B = 4096 B)
 * |   for each slot:            |
 * |     offset      : u64       |
 * |     length      : u32       |
 * |     uncompr     : u32       |
 * +-----------------------------+
 * | Tile blob 0 (zstd payload)  |
 * | Tile blob 1                 |
 * | ...                         |
 * +-----------------------------+
 *
 * Tile blob (uncompressed payload, zstd-compressed on disk):
 *   sectionCount : u8
 *   for each section:
 *     type             : u8   (0 = NOISE)
 *     uncompressedSize : u32
 *     payload bytes    : raw section content
 * </pre>
 */
public final class AtrFormat {

    private AtrFormat() {}

    public static final int MAGIC = 0x41545231; // "ATR1" big-endian
    public static final int VERSION = 1;

    public static final int HEADER_SIZE  = 64;
    public static final int INDEX_ENTRY_SIZE = 16;
    public static final int INDEX_SIZE   = RegionCoord.TILES_PER_REGION * INDEX_ENTRY_SIZE; // 4096
    public static final int DATA_OFFSET  = HEADER_SIZE + INDEX_SIZE;                        // 4160

    /** Section type tags. */
    public static final byte SECTION_NOISE  = 0;
    public static final byte SECTION_BIOMES = 1; // sub-plan 6
    public static final byte SECTION_BLOCKS = 2; // sub-plan 6
}
