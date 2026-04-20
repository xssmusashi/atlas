package dev.xssmusashi.atlas.core.region;

import dev.xssmusashi.atlas.core.tile.TileCoord;

/**
 * Region coordinate. One region groups {@link #TILES_PER_REGION_AXIS}² tiles
 * = 16×16 = 256 tiles = 128×128 chunks (one .atr file per region).
 */
public record RegionCoord(int regionX, int regionZ) {

    public static final int TILES_PER_REGION_AXIS = 16;
    public static final int TILES_PER_REGION = TILES_PER_REGION_AXIS * TILES_PER_REGION_AXIS;

    public static RegionCoord fromTile(TileCoord tile) {
        return new RegionCoord(
            Math.floorDiv(tile.tileX(), TILES_PER_REGION_AXIS),
            Math.floorDiv(tile.tileZ(), TILES_PER_REGION_AXIS)
        );
    }

    /** Convert a tile coord to its 0..255 slot index inside its region. */
    public static int slotInRegion(TileCoord tile) {
        int localX = Math.floorMod(tile.tileX(), TILES_PER_REGION_AXIS);
        int localZ = Math.floorMod(tile.tileZ(), TILES_PER_REGION_AXIS);
        return localZ * TILES_PER_REGION_AXIS + localX;
    }

    /** Default file name layout (e.g. {@code r.0.-1.atr}). */
    public String fileName() {
        return "r." + regionX + "." + regionZ + ".atr";
    }
}
