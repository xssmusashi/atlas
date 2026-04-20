package dev.xssmusashi.atlas.core.tile;

/**
 * Lifecycle states of a {@link Tile}. Stages assert preconditions and atomically advance state.
 * Phase 1 implements only NEW → NOISE → DONE.
 */
public enum TileState {
    NEW,
    NOISE,      // noise field populated
    BIOMES,     // (sub-plan 5)
    SURFACE,    // (sub-plan 5)
    CARVED,     // (sub-plan 5)
    FEATURED,   // (sub-plan 6)
    LIT,        // (sub-plan 6)
    DONE        // ready for consumer
}
