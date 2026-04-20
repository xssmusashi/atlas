package dev.xssmusashi.atlas.core.tile;

/**
 * Coordinate of an Atlas tile. One tile spans 8×8 chunks = 128×128 blocks horizontally,
 * full world height vertically.
 *
 * @param tileX tile X (block X = tileX × {@link Tile#TILE_BLOCKS})
 * @param tileZ tile Z
 */
public record TileCoord(int tileX, int tileZ) {

    /** Map a chunk position to its enclosing tile. */
    public static TileCoord fromChunk(int chunkX, int chunkZ) {
        return new TileCoord(Math.floorDiv(chunkX, 8), Math.floorDiv(chunkZ, 8));
    }

    /** Map block coordinates to enclosing tile. */
    public static TileCoord fromBlock(int blockX, int blockZ) {
        return new TileCoord(
            Math.floorDiv(blockX, Tile.TILE_BLOCKS),
            Math.floorDiv(blockZ, Tile.TILE_BLOCKS)
        );
    }

    /** Block X of cell (0,0) in this tile. */
    public int baseBlockX() { return tileX * Tile.TILE_BLOCKS; }

    /** Block Z of cell (0,0) in this tile. */
    public int baseBlockZ() { return tileZ * Tile.TILE_BLOCKS; }
}
