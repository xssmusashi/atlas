package dev.xssmusashi.atlas.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import dev.xssmusashi.atlas.core.region.RegionCoord;
import dev.xssmusashi.atlas.core.region.RegionFile;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import dev.xssmusashi.atlas.core.tile.TilePipeline;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * In-game commands for inspecting and benchmarking Atlas. Built against MC 26.1+
 * Mojang names (unobfuscated MC).
 *
 * <ul>
 *   <li>{@code /atlas info} — version, JIT mode, parallelism, current state</li>
 *   <li>{@code /atlas bench} — run a small tile-pipeline benchmark and report cps in chat</li>
 *   <li>{@code /atlas validate} — verify JIT vs interpreter on a random tree (correctness check)</li>
 * </ul>
 */
public final class AtlasCommand {

    private AtlasCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static final Path ATLAS_DATA_DIR = Paths.get("atlas-tiles");

    /** Per-tick budget for force-load ticket additions (avoids server-thread freeze). */
    private static final int FORCE_LOAD_BATCH_SIZE = 64;

    /** State of the currently-running vanilla pregen, if any. */
    private static final AtomicReference<VanillaPregenState> activePregen = new AtomicReference<>();

    private record VanillaPregenState(AtomicBoolean cancelled, int totalChunks) {}

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("atlas")
                .then(Commands.literal("info").executes(AtlasCommand::executeInfo))
                .then(Commands.literal("bench").executes(AtlasCommand::executeBench))
                .then(Commands.literal("validate").executes(AtlasCommand::executeValidate))
                .then(Commands.literal("pregen")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(ctx -> executePregen(ctx, false, -1))
                        .then(Commands.literal("persist")
                            .executes(ctx -> executePregen(ctx, true, -1))
                            .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> executePregen(ctx, true, IntegerArgumentType.getInteger(ctx, "threads")))))
                        .then(Commands.argument("threads", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> executePregen(ctx, false, IntegerArgumentType.getInteger(ctx, "threads"))))))
                .then(Commands.literal("map").executes(AtlasCommand::executeMap))
                .then(Commands.literal("list").executes(AtlasCommand::executeList))
                .then(Commands.literal("pregen-vanilla")
                    .then(Commands.argument("radius", IntegerArgumentType.integer(1, 256))
                        .executes(AtlasCommand::executePregenVanilla)))
                .then(Commands.literal("cancel").executes(AtlasCommand::executeCancel))
                .then(Commands.literal("accelerate")
                    .then(Commands.literal("on").executes(ctx -> setAccelerate(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setAccelerate(ctx, false)))
                    .then(Commands.literal("status").executes(AtlasCommand::accelerateStatus)))
                .then(Commands.literal("profile").executes(AtlasCommand::executeProfile))
        );
    }

    private static int executeInfo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        boolean vectorAvailable = isVectorApiAvailable();
        var stats = dev.xssmusashi.atlas.mc.AtlasMixinStats.class;
        boolean tickMixin = dev.xssmusashi.atlas.mc.AtlasMixinStats.serverTickMixinActive();
        boolean chunkGenMixin = dev.xssmusashi.atlas.mc.AtlasMixinStats.chunkGenMixinActive();
        boolean noisePopulateMixin = dev.xssmusashi.atlas.mc.AtlasMixinStats.noisePopulateMixinActive();
        long ticks = dev.xssmusashi.atlas.mc.AtlasMixinStats.ticks();
        long chunkGens = dev.xssmusashi.atlas.mc.AtlasMixinStats.chunkGenSeen();
        long noisePops = dev.xssmusashi.atlas.mc.AtlasMixinStats.noisePopulateIntercepts();

        sendMessage(src, "§6§l[Atlas] §rstatus");
        sendMessage(src, "§7  version:       §f0.1.0-SNAPSHOT (Phase 1)");
        sendMessage(src, "§7  cores:         §f" + cores);
        sendMessage(src, "§7  max heap:      §f" + maxHeap + " MB");
        sendMessage(src, "§7  Vector API:    §f" + (vectorAvailable ? "§aAVAILABLE" : "§cMISSING"));
        sendMessage(src, "§7  Mixins:");
        sendMessage(src, "§7    server-tick:   §f" + (tickMixin ? "§a✓ " + ticks + " ticks" : "§c✗"));
        sendMessage(src, "§7    chunkgen-init: §f" + (chunkGenMixin ? "§a✓ " + chunkGens + " gens seen" : "§c✗"));
        sendMessage(src, "§7    noise-populate:§f" + (noisePopulateMixin ? "§a✓ " + noisePops + " calls" : "§c✗"));

        // Substitute mixin status
        boolean accelOn = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.isSubstituteEnabled();
        long subs = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.substitutedCalls();
        long fb = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.fallbackCalls();
        long mismatches = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.verifyMismatches();
        int convEntries = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.convertibleEntries();
        sendMessage(src, "§7    substitute:    §f" + (accelOn ? "§aON" : "§7off")
            + " §7(" + convEntries + " gens convertible, " + subs + " calls subs / " + fb + " fb / " + mismatches + " mismatch)");

        // Vanilla-vs-Atlas timing comparison (live data from this MC session).
        long doFillCalls = dev.xssmusashi.atlas.mc.AtlasMixinStats.doFillCalls();
        double vanillaMsPerChunk = dev.xssmusashi.atlas.mc.AtlasMixinStats.doFillAvgMs();
        if (doFillCalls > 0) {
            double vanillaCps = 1000.0 / vanillaMsPerChunk;
            sendMessage(src, "§7  §6Vanilla noise (live):§f " + String.format("%.1f ms/chunk", vanillaMsPerChunk)
                + " §7(" + doFillCalls + " samples) ≈ §f" + String.format("%.0f cps", vanillaCps) + " §7single-thread");
            sendMessage(src, "§7  §6Atlas potential:§f run §e/atlas bench§f to compare on identical workload");
        } else {
            sendMessage(src, "§7  vanilla timing: §c(no doFill calls yet — explore the world to load chunks)");
        }
        sendMessage(src, "§7Try: §e/atlas bench§7 or §e/atlas pregen 32 persist 4");
        return 1;
    }

    private static boolean isVectorApiAvailable() {
        try {
            Class.forName("jdk.incubator.vector.DoubleVector");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static int executeBench(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int tilesToGen = 8;

        sendMessage(src, "§6§l[Atlas] §rrunning bench: " + tilesToGen + " tiles, parallelism " + parallelism + "...");

        DfcNode tree = buildBenchTree();
        CompiledSampler sampler = JitCompiler.compile(tree, JitOptions.DEFAULT);

        try (DagScheduler sched = new DagScheduler(parallelism, parallelism * 2);
             TilePipeline pipeline = new TilePipeline(0xC0FFEEL, sampler, sched)) {

            long t0 = System.nanoTime();
            CompletableFuture<?>[] futs = new CompletableFuture[tilesToGen];
            for (int i = 0; i < tilesToGen; i++) {
                futs[i] = pipeline.generate(new TileCoord(i * 4, 0));
            }
            try {
                CompletableFuture.allOf(futs).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendMessage(src, "§c[Atlas] interrupted");
                return 0;
            } catch (ExecutionException ee) {
                sendMessage(src, "§c[Atlas] failed: " + ee.getCause().getMessage());
                return 0;
            }
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            int chunks = tilesToGen * 64;
            double cps = chunks * 1000.0 / Math.max(1, elapsedMs);

            sendMessage(src, "§6§l[Atlas] §rresult:");
            sendMessage(src, "§7  generated:     §f" + chunks + " chunks (" + tilesToGen + " tiles)");
            sendMessage(src, "§7  elapsed:       §f" + elapsedMs + " ms");
            sendMessage(src, "§7  throughput:    §a" + String.format("%.1f cps", cps));
            sendMessage(src, "§7  parallelism:   §f" + parallelism + " threads");
            sendMessage(src, "§7  (noise-only stage; full pipeline arrives in Phase 2)");

            for (CompletableFuture<?> f : futs) {
                try { ((Tile) f.get()).close(); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    private static int executePregen(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx,
                                      boolean persist, int requestedThreads) {
        CommandSourceStack src = ctx.getSource();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");      // chunks
        // Default: half the cores. User can override via threads argument.
        int parallelism = requestedThreads > 0
            ? requestedThreads
            : Math.max(1, Runtime.getRuntime().availableProcessors() / 2);

        // Square area: side = 2 * radius chunks. Round up to whole tiles (8 chunks each).
        int sideChunks = radius * 2;
        int sideTiles  = (sideChunks + 7) / 8;
        int tiles      = sideTiles * sideTiles;
        int actualChunks = tiles * 64;

        int playerChunkX, playerChunkZ;
        try {
            var pos = src.getPosition();
            playerChunkX = (int) Math.floor(pos.x() / 16.0);
            playerChunkZ = (int) Math.floor(pos.z() / 16.0);
        } catch (Throwable t) {
            playerChunkX = 0;
            playerChunkZ = 0;
        }
        // Origin tile is the tile containing the south-west corner of the area.
        int originChunkX = playerChunkX - radius;
        int originChunkZ = playerChunkZ - radius;
        int originTileX  = Math.floorDiv(originChunkX, 8);
        int originTileZ  = Math.floorDiv(originChunkZ, 8);

        sendMessage(src, "§6§l[Atlas] §rpregen: radius " + radius + " chunks → "
            + sideChunks + "×" + sideChunks + " square ("
            + actualChunks + " chunks, " + sideTiles + "×" + sideTiles + " tiles), "
            + "parallelism " + parallelism
            + (persist ? ", §epersist→atlas-tiles/" : "") + "...");

        DfcNode tree = buildBenchTree();
        CompiledSampler sampler = JitCompiler.compile(tree, JitOptions.DEFAULT);

        // Pre-open region files for persistence (concurrent map — multiple worker
        // threads complete tiles in parallel and may need region-file handles).
        Map<RegionCoord, RegionFile> openRegions = new ConcurrentHashMap<>();
        try {
            if (persist) {
                Files.createDirectories(ATLAS_DATA_DIR);
            }
        } catch (IOException io) {
            sendMessage(src, "§c[Atlas] cannot create atlas-tiles dir: " + io.getMessage());
            return 0;
        }

        AtomicLong peakHeapTracker = new AtomicLong();
        long t0 = System.nanoTime();
        long peakHeapBytes;
        long bytesWritten = 0;
        // Background pool: low-priority workers so MC's render/tick threads keep
        // CPU when contended. Without this, /atlas pregen makes the game unplayable.
        try (DagScheduler sched = new DagScheduler(parallelism, parallelism * 4, Thread.MIN_PRIORITY);
             TilePipeline pipeline = new TilePipeline(0xC0FFEEL, sampler, sched)) {

            // STREAM each tile: as soon as a tile completes on a worker thread,
            // serialize → write → close → evict. This caps in-flight memory at
            // (parallelism × tile-size) ≈ 750 MB instead of 31 GB for radius=100.
            CompletableFuture<?>[] futs = new CompletableFuture[tiles];
            int submitted = 0;
            for (int dz = 0; dz < sideTiles; dz++) {
                for (int dx = 0; dx < sideTiles; dx++) {
                    final TileCoord tc = new TileCoord(originTileX + dx, originTileZ + dz);
                    futs[submitted++] = pipeline.generate(tc).thenAccept(tile -> {
                        try {
                            if (persist) {
                                RegionCoord rc = RegionCoord.fromTile(tile.coord);
                                RegionFile rf = openRegions.computeIfAbsent(rc, key -> {
                                    try { return RegionFile.open(ATLAS_DATA_DIR, key); }
                                    catch (IOException e) { throw new RuntimeException(e); }
                                });
                                rf.writeTile(tile); // RegionFile.writeTile is synchronized
                            }
                        } catch (Exception e) {
                            AtlasModLog.warn("Failed to persist tile " + tile.coord + ": " + e);
                        } finally {
                            tile.close();              // free off-heap arena IMMEDIATELY
                            pipeline.evict(tile.coord); // remove from in-flight cache
                            long heap = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                            peakHeapTracker.accumulateAndGet(heap, Math::max);
                        }
                    });
                }
            }
            try {
                CompletableFuture.allOf(futs).get();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                sendMessage(src, "§c[Atlas] interrupted");
                return 0;
            } catch (ExecutionException ee) {
                sendMessage(src, "§c[Atlas] failed: " + ee.getCause().getMessage());
                return 0;
            }

            if (persist) {
                for (RegionFile rf : openRegions.values()) {
                    try { rf.close(); } catch (IOException ignored) {}
                }
                try (var stream = Files.list(ATLAS_DATA_DIR)) {
                    bytesWritten = stream.filter(p -> p.getFileName().toString().endsWith(".atr"))
                        .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                        .sum();
                } catch (IOException ignored) {}
            }
        }
        peakHeapBytes = peakHeapTracker.get();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        double cps = actualChunks * 1000.0 / Math.max(1, elapsedMs);
        double tilesPerSec = tiles * 1000.0 / Math.max(1, elapsedMs);

        sendMessage(src, "§6§l[Atlas] §rpregen complete:");
        sendMessage(src, "§7  generated:     §a" + actualChunks + " chunks");
        sendMessage(src, "§7  elapsed:       §f" + elapsedMs + " ms");
        sendMessage(src, "§7  throughput:    §a" + String.format("%.1f cps §7(%.1f tiles/sec)", cps, tilesPerSec));
        sendMessage(src, "§7  parallelism:   §f" + parallelism + " threads");
        sendMessage(src, "§7  peak heap:     §f" + (peakHeapBytes / (1024 * 1024)) + " MB");
        sendMessage(src, "§7  centred on:    §fchunk (" + playerChunkX + ", " + playerChunkZ
            + ") = block (" + (playerChunkX * 16) + ", " + (playerChunkZ * 16) + ")");
        if (persist) {
            sendMessage(src, "§7  persisted:     §a" + (bytesWritten / 1024) + " KB to ./atlas-tiles/");
            sendMessage(src, "§7  try: §e/atlas list§7 / §e/atlas map");
        } else {
            sendMessage(src, "§7  (in-memory only — add §epersist§7 to write .atr files)");
        }
        return 1;
    }

    private static int executeMap(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        // Build sampler and read a 16x16 heightmap centred on player position.
        DfcNode tree = buildBenchTree();
        CompiledSampler sampler = JitCompiler.compile(tree, JitOptions.DEFAULT);

        int playerBlockX, playerBlockZ;
        try {
            var pos = src.getPosition();
            playerBlockX = (int) Math.floor(pos.x());
            playerBlockZ = (int) Math.floor(pos.z());
        } catch (Throwable t) {
            playerBlockX = 0;
            playerBlockZ = 0;
        }

        // 16x16 tile sampled at 16-block stride (so we cover 256x256 blocks).
        int stride = 16;
        int half = 8 * stride;
        long seed = 0xC0FFEEL;

        int[][] heights = new int[16][16];
        int minH = Integer.MAX_VALUE, maxH = Integer.MIN_VALUE;
        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int worldX = playerBlockX - half + dx * stride;
                int worldZ = playerBlockZ - half + dz * stride;
                // Walk Y from top to find first y where noise crosses zero.
                int h = -64;
                for (int y = 320; y >= -64; y--) {
                    if (sampler.sample(worldX, y, worldZ, seed) > 0.0) { h = y; break; }
                }
                heights[dz][dx] = h;
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
        }
        int range = Math.max(1, maxH - minH);

        sendMessage(src, "§6§l[Atlas] §rheightmap (16x16, stride=" + stride + ", centred on you):");
        sendMessage(src, "§7  height range: §f[" + minH + ", " + maxH + "]  §7span: §f" + range);
        char[] palette = {'.', '-', '~', '=', 'o', 'x', 'X', '#', '@'};
        for (int dz = 0; dz < 16; dz++) {
            StringBuilder line = new StringBuilder("§8| ");
            for (int dx = 0; dx < 16; dx++) {
                int h = heights[dz][dx];
                int idx = (h - minH) * (palette.length - 1) / range;
                String colour = idx < 2 ? "§9" : idx < 4 ? "§b" : idx < 6 ? "§a" : idx < 8 ? "§e" : "§c";
                line.append(colour).append(palette[idx]).append(palette[idx]);
            }
            line.append(" §8|");
            sendMessage(src, line.toString());
        }
        sendMessage(src, "§7  (deeper §9blue §7→ low, §chot red §7→ high; based on JIT noise field)");
        return 1;
    }

    private static int executeList(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!Files.isDirectory(ATLAS_DATA_DIR)) {
            sendMessage(src, "§7[Atlas] no atlas-tiles/ directory yet — run §e/atlas pregen 512 persist§7 first.");
            return 0;
        }
        try (var stream = Files.list(ATLAS_DATA_DIR)) {
            int[] count = {0};
            long[] totalBytes = {0};
            stream.filter(p -> p.getFileName().toString().endsWith(".atr"))
                .forEach(p -> {
                    try {
                        long size = Files.size(p);
                        count[0]++;
                        totalBytes[0] += size;
                        if (count[0] <= 10) {
                            sendMessage(src, "§7  §f" + p.getFileName() + " §8(" + (size / 1024) + " KB)");
                        }
                    } catch (IOException ignored) {}
                });
            sendMessage(src, "§6§l[Atlas] §r" + count[0] + " region files, total "
                + (totalBytes[0] / 1024) + " KB" + (count[0] > 10 ? " (showing first 10)" : ""));
        } catch (IOException io) {
            sendMessage(src, "§c[Atlas] list failed: " + io.getMessage());
            return 0;
        }
        return 1;
    }

    /**
     * Pre-generates real vanilla chunks via MC's ticket system. Atlas adds an
     * UNKNOWN ticket for each chunk in the area; MC's chunk loader then dispatches
     * generation to its own worker pool (parallel by design). Atlas polls in a
     * daemon thread to detect completion, reports progress, and removes tickets
     * when done. No mixin into worldgen — MC's pipeline runs normally so Terralith
     * + datapacks produce identical terrain to walking there.
     */
    private static int executePregenVanilla(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int radius = IntegerArgumentType.getInteger(ctx, "radius");

        ServerLevel level;
        MinecraftServer server;
        int playerChunkX, playerChunkZ;
        try {
            level = src.getLevel();
            server = level.getServer();
            if (server == null) {
                sendMessage(src, "§c[Atlas] no server (run from a real world, not main menu)");
                return 0;
            }
            var pos = src.getPosition();
            playerChunkX = (int) Math.floor(pos.x() / 16.0);
            playerChunkZ = (int) Math.floor(pos.z() / 16.0);
        } catch (Throwable t) {
            sendMessage(src, "§c[Atlas] cannot get server context: " + t.getMessage());
            return 0;
        }

        int side = radius * 2;
        int total = side * side;

        // Build full chunk position list once (used both for queueing and polling).
        final List<ChunkPos> positions = new ArrayList<>(total);
        for (int dz = -radius; dz < radius; dz++) {
            for (int dx = -radius; dx < radius; dx++) {
                positions.add(new ChunkPos(playerChunkX + dx, playerChunkZ + dz));
            }
        }

        sendMessage(src, "§6§l[Atlas] §rvanilla pregen (ticket-based parallel): " + total
            + " chunks (radius " + radius + ", " + side + "×" + side + ") around chunk ("
            + playerChunkX + "," + playerChunkZ + ")...");
        sendMessage(src, "§7  uses MC's own worker pool — Terralith + datapacks unchanged. Voxy will see real .mca.");

        final long t0 = System.nanoTime();
        final AtomicInteger reportedLoaded = new AtomicInteger();

        // Set up cancellation state so /atlas cancel can stop us mid-flight.
        VanillaPregenState state = new VanillaPregenState(new AtomicBoolean(false), total);
        VanillaPregenState prev = activePregen.getAndSet(state);
        if (prev != null) prev.cancelled().set(true);

        // Phase 1: force-load chunks BATCHED across server ticks. Adding 16k
        // tickets in one server.execute freezes the tick loop; instead we
        // schedule batches of FORCE_LOAD_BATCH_SIZE per tick via repeating
        // server.execute. Server stays responsive throughout queueing.
        scheduleBatchedForceLoad(server, level, positions, state, 0);

        // Phase 2: poll for completion in a daemon thread.
        Thread poller = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(2000);
                    if (state.cancelled().get()) {
                        // Cleanup whatever we managed to force-load.
                        server.execute(() -> {
                            try {
                                for (ChunkPos pos : positions) {
                                    level.setChunkForced(pos.x(), pos.z(), false);
                                }
                            } catch (Throwable ignored) {}
                            sendMessage(src, "§e[Atlas] vanilla pregen cancelled.");
                            activePregen.compareAndSet(state, null);
                        });
                        return;
                    }
                    int loaded = 0;
                    for (ChunkPos pos : positions) {
                        if (level.hasChunk(pos.x(), pos.z())) loaded++;
                    }
                    long now = System.nanoTime();
                    long elapsedMs = (now - t0) / 1_000_000;

                    if (loaded != reportedLoaded.get()) {
                        reportedLoaded.set(loaded);
                        double cps = loaded * 1000.0 / Math.max(1, elapsedMs);
                        final int snap = loaded;
                        final double snapCps = cps;
                        server.execute(() -> sendMessage(src,
                            "§7  …" + snap + "/" + total + " chunks (§a"
                                + String.format("%.1f cps", snapCps) + "§7)"));
                    }

                    if (loaded >= total) {
                        final double finalCps = loaded * 1000.0 / Math.max(1, elapsedMs);
                        final int finalLoaded = loaded;
                        final long finalElapsed = elapsedMs;
                        server.execute(() -> {
                            try {
                                for (ChunkPos pos : positions) {
                                    level.setChunkForced(pos.x(), pos.z(), false);
                                }
                            } catch (Throwable ignored) {}
                            sendMessage(src, "§6§l[Atlas] §rvanilla pregen complete:");
                            sendMessage(src, "§7  generated:  §a" + finalLoaded + " chunks");
                            sendMessage(src, "§7  elapsed:    §f" + finalElapsed + " ms");
                            sendMessage(src, "§7  throughput: §a" + String.format("%.1f cps", finalCps));
                            sendMessage(src, "§7  (real .mca chunks; Voxy and game both see them)");
                            activePregen.compareAndSet(state, null);
                        });
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                server.execute(() -> sendMessage(src, "§c[Atlas] poller failed: " + t.getMessage()));
            }
        }, "atlas-vanilla-pregen-poller");
        poller.setDaemon(true);
        poller.start();

        sendMessage(src, "§7  queueing in batches of " + FORCE_LOAD_BATCH_SIZE
            + "/tick to keep server responsive. /atlas cancel to abort.");
        return 1;
    }

    /** Recursively schedule batched force-load on the server thread, one batch per tick. */
    private static void scheduleBatchedForceLoad(MinecraftServer server, ServerLevel level,
                                                  List<ChunkPos> positions,
                                                  VanillaPregenState state, int startIdx) {
        if (state.cancelled().get()) return;
        server.execute(() -> {
            if (state.cancelled().get()) return;
            int end = Math.min(startIdx + FORCE_LOAD_BATCH_SIZE, positions.size());
            try {
                for (int i = startIdx; i < end; i++) {
                    ChunkPos pos = positions.get(i);
                    level.setChunkForced(pos.x(), pos.z(), true);
                }
            } catch (Throwable ignored) {}
            if (end < positions.size()) {
                scheduleBatchedForceLoad(server, level, positions, state, end);
            }
        });
    }

    private static int setAccelerate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean on) {
        CommandSourceStack src = ctx.getSource();
        dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.setSubstituteEnabled(on);
        sendMessage(src, "§6[Atlas] §rsubstitute mode: " + (on ? "§aON" : "§7OFF"));
        if (on) {
            sendMessage(src, "§7  WARNING: substituting density evaluation. If terrain looks wrong, /atlas accelerate off");
        }
        return 1;
    }

    private static int accelerateStatus(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        var ar = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.class;
        boolean on = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.isSubstituteEnabled();
        long subs = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.substitutedCalls();
        long fb = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.fallbackCalls();
        int cache = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.cacheSize();
        int conv = dev.xssmusashi.atlas.mc.bridge.AcceleratedRouter.convertibleEntries();
        long bridgeOk = dev.xssmusashi.atlas.mc.bridge.DfcBridge.convertedCount();
        long bridgeFail = dev.xssmusashi.atlas.mc.bridge.DfcBridge.unconvertedCount();
        var unsupported = dev.xssmusashi.atlas.mc.bridge.DfcBridge.unsupportedTypes();

        sendMessage(src, "§6§l[Atlas] §raccelerator status:");
        sendMessage(src, "§7  substitute mode:    §f" + (on ? "§aON" : "§7OFF"));
        sendMessage(src, "§7  generators cached:  §f" + cache + " (§a" + conv + " convertible§7)");
        sendMessage(src, "§7  DfcBridge conv ok:  §a" + bridgeOk + "§7, fail: §c" + bridgeFail);
        sendMessage(src, "§7  substituted calls:  §a" + subs);
        sendMessage(src, "§7  fallback calls:     §c" + fb);
        if (!unsupported.isEmpty()) {
            sendMessage(src, "§7  unsupported types (top 5):");
            int count = 0;
            for (String t : unsupported) {
                sendMessage(src, "§7    " + t.substring(Math.max(0, t.lastIndexOf('.') + 1)));
                if (++count >= 5) break;
            }
        }
        return 1;
    }

    private static int executeProfile(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        Runtime rt = Runtime.getRuntime();
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long allocMb = rt.totalMemory() / (1024 * 1024);
        int cores = rt.availableProcessors();
        double heapPct = 100.0 * usedMb / maxMb;
        var thread = java.lang.management.ManagementFactory.getThreadMXBean();
        int threadCount = thread.getThreadCount();

        sendMessage(src, "§6§l[Atlas] §rprofile (now):");
        sendMessage(src, "§7  cores:        §f" + cores);
        sendMessage(src, "§7  heap:         §f" + usedMb + " / " + allocMb + " / " + maxMb + " MB"
            + " (§a" + String.format("%.0f%%", heapPct) + "§7 used)");
        sendMessage(src, "§7  JVM threads:  §f" + threadCount);

        // Recommendation
        if (heapPct > 80) {
            sendMessage(src, "§c  recommend:   §rlower /atlas pregen threads or wait — heap pressure high");
        } else if (heapPct < 40 && allocMb < maxMb / 2) {
            sendMessage(src, "§a  recommend:   §rcan use more threads (e.g. /atlas pregen 32 " + (cores - 1) + ")");
        } else {
            sendMessage(src, "§7  recommend:   §rcurrent settings are balanced");
        }
        return 1;
    }

    private static int executeCancel(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        VanillaPregenState s = activePregen.get();
        if (s == null) {
            sendMessage(src, "§e[Atlas] no active pregen to cancel.");
            return 0;
        }
        s.cancelled().set(true);
        sendMessage(src, "§e[Atlas] cancellation requested — cleanup on next poll cycle (~2s).");
        return 1;
    }

    private static int executeValidate(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        sendMessage(src, "§6§l[Atlas] §rrunning JIT vs Interpreter correctness check...");

        DfcNode tree = buildBenchTree();
        CompiledSampler interp = new dev.xssmusashi.atlas.core.jit.Interpreter(tree);
        CompiledSampler jit = JitCompiler.compile(tree, JitOptions.DEFAULT);

        java.util.Random rng = new java.util.Random(0xA71A5L);
        int samples = 1000;
        int mismatches = 0;
        for (int i = 0; i < samples; i++) {
            int x = rng.nextInt(2000) - 1000;
            int y = rng.nextInt(384) - 64;
            int z = rng.nextInt(2000) - 1000;
            long seed = rng.nextLong();
            double a = jit.sample(x, y, z, seed);
            double b = interp.sample(x, y, z, seed);
            if (Double.doubleToRawLongBits(a) != Double.doubleToRawLongBits(b)) {
                mismatches++;
            }
        }

        if (mismatches == 0) {
            sendMessage(src, "§a[Atlas] §rPASS — JIT bit-exact with Interpreter on " + samples + " random samples.");
            return 1;
        } else {
            sendMessage(src, "§c[Atlas] FAIL — " + mismatches + "/" + samples + " mismatches. Report this!");
            return 0;
        }
    }

    private static DfcNode buildBenchTree() {
        DfcNode continentalness = new DfcNode.OctavePerlin(
            0L, 8, 0.5, 2.0, 0.005,
            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()
        );
        DfcNode erosion = new DfcNode.OctavePerlin(
            12345L, 6, 0.5, 2.0, 0.012,
            new DfcNode.XPos(), new DfcNode.Constant(0), new DfcNode.ZPos()
        );
        return new DfcNode.Clamp(
            new DfcNode.Sub(
                new DfcNode.Add(
                    new DfcNode.Mul(continentalness, new DfcNode.Constant(1.5)),
                    new DfcNode.Mul(erosion, new DfcNode.Constant(0.5))
                ),
                new DfcNode.Mul(
                    new DfcNode.Sub(new DfcNode.YPos(), new DfcNode.Constant(64)),
                    new DfcNode.Constant(1.0 / 96.0)
                )
            ),
            -1.0, 1.0
        );
    }

    private static void sendMessage(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal(message), false);
    }
}
