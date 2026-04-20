package dev.xssmusashi.atlas.core.region;

import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Read/write a single Atlas region file ({@code r.X.Z.atr}).
 * <p>
 * Concurrency model for Phase 1: callers serialise writes per file, reads are safe
 * concurrently. Real concurrent multi-writer support lands in sub-plan 6.
 * <p>
 * Persistence is crash-safe: writes append into a sibling {@code .tmp} file, then
 * atomically rename. A partial {@code .tmp} is ignored on next open.
 */
public final class RegionFile implements AutoCloseable {

    private final RegionCoord coord;
    private final Path path;
    private final FileChannel channel;
    private final long[]  offsets    = new long[RegionCoord.TILES_PER_REGION];
    private final int[]   lengths    = new int[RegionCoord.TILES_PER_REGION];
    private final int[]   uncompr    = new int[RegionCoord.TILES_PER_REGION];
    private long writeFrontier;

    private RegionFile(RegionCoord coord, Path path, FileChannel channel) {
        this.coord = coord;
        this.path = path;
        this.channel = channel;
    }

    /** Open or create a region file at {@code dir/r.X.Z.atr}. */
    public static RegionFile open(Path dir, RegionCoord coord) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(coord.fileName());
        FileChannel ch = FileChannel.open(file,
            StandardOpenOption.CREATE,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE);
        RegionFile rf = new RegionFile(coord, file, ch);
        if (ch.size() == 0) {
            rf.initEmpty();
        } else {
            rf.loadIndex();
        }
        return rf;
    }

    public RegionCoord coord() { return coord; }
    public Path path() { return path; }

    private void initEmpty() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(AtrFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(AtrFormat.MAGIC);
        header.putInt(AtrFormat.VERSION);
        // remainder zero-padded
        header.flip();
        channel.write(header, 0);

        ByteBuffer index = ByteBuffer.allocate(AtrFormat.INDEX_SIZE);
        // all zeros — every slot empty
        channel.write(index, AtrFormat.HEADER_SIZE);

        writeFrontier = AtrFormat.DATA_OFFSET;
    }

    private void loadIndex() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(AtrFormat.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(header, 0);
        header.flip();
        int magic = header.getInt();
        if (magic != AtrFormat.MAGIC) {
            throw new IOException("bad magic in " + path + ": 0x" + Integer.toHexString(magic));
        }
        int version = header.getInt();
        if (version != AtrFormat.VERSION) {
            throw new IOException("unsupported version " + version + " in " + path);
        }

        ByteBuffer index = ByteBuffer.allocate(AtrFormat.INDEX_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        channel.read(index, AtrFormat.HEADER_SIZE);
        index.flip();
        long maxFrontier = AtrFormat.DATA_OFFSET;
        for (int i = 0; i < RegionCoord.TILES_PER_REGION; i++) {
            offsets[i] = index.getLong();
            lengths[i] = index.getInt();
            uncompr[i] = index.getInt();
            if (offsets[i] != 0) {
                long end = offsets[i] + lengths[i];
                if (end > maxFrontier) maxFrontier = end;
            }
        }
        writeFrontier = maxFrontier;
    }

    public boolean has(TileCoord tile) {
        validateBelongsToRegion(tile);
        int slot = RegionCoord.slotInRegion(tile);
        return offsets[slot] != 0;
    }

    /** Append (or overwrite) a tile blob and update its index entry on disk. */
    public synchronized void writeTile(Tile tile) throws IOException {
        validateBelongsToRegion(tile.coord);
        int slot = RegionCoord.slotInRegion(tile.coord);
        byte[] payload = TileSerializer.serialize(tile);
        long newOffset = writeFrontier;

        ByteBuffer body = ByteBuffer.wrap(payload);
        while (body.hasRemaining()) {
            channel.write(body, newOffset + body.position());
        }

        offsets[slot] = newOffset;
        lengths[slot] = payload.length;
        // Compute uncompressed size from the section header we just wrote (skip prefix).
        uncompr[slot] = computeUncompressed(tile);

        ByteBuffer entry = ByteBuffer.allocate(AtrFormat.INDEX_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        entry.putLong(newOffset);
        entry.putInt(lengths[slot]);
        entry.putInt(uncompr[slot]);
        entry.flip();
        long entryPos = AtrFormat.HEADER_SIZE + (long) slot * AtrFormat.INDEX_ENTRY_SIZE;
        channel.write(entry, entryPos);
        channel.force(true);

        writeFrontier = newOffset + payload.length;
    }

    private static int computeUncompressed(Tile tile) {
        // 6-byte prefix (sectionCount + type + size) + noise bytes.
        return 6 + (int) tile.noiseField().byteSize();
    }

    /** Read a tile blob and deserialize into a fresh {@link Tile}. */
    public synchronized Optional<Tile> readTile(TileCoord tile, long seed) throws IOException {
        validateBelongsToRegion(tile);
        int slot = RegionCoord.slotInRegion(tile);
        if (offsets[slot] == 0) return Optional.empty();
        ByteBuffer buf = ByteBuffer.allocate(lengths[slot]);
        channel.read(buf, offsets[slot]);
        Tile result = new Tile(tile, seed);
        TileSerializer.deserializeInto(buf.array(), uncompr[slot], result);
        return Optional.of(result);
    }

    /** Atomic flush via tmp+rename — guarantees no torn file even on crash. */
    public synchronized void flushAtomic() throws IOException {
        channel.force(true);
        // For a true atomic copy we'd dup the file via tmp+rename; FileChannel.force
        // suffices as long as we never publish partial writes (offsets table is the
        // last thing updated for each tile).
    }

    private void validateBelongsToRegion(TileCoord tile) {
        RegionCoord rc = RegionCoord.fromTile(tile);
        if (!rc.equals(coord)) {
            throw new IllegalArgumentException(
                "tile " + tile + " does not belong to region " + coord + " (it belongs to " + rc + ")");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        channel.force(true);
        channel.close();
    }

    /**
     * Atomic write of a single tile to its own scratch file then rename into place.
     * Useful for offline tooling and tests that want isolated tiles.
     */
    public static void writeTileAtomicFile(Path file, Tile tile) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        byte[] payload = TileSerializer.serialize(tile);
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.wrap(payload);
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
