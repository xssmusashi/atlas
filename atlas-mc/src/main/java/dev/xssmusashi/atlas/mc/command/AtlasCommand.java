package dev.xssmusashi.atlas.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import dev.xssmusashi.atlas.core.tile.TilePipeline;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

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

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Read-only commands — safe for any player.
        dispatcher.register(
            Commands.literal("atlas")
                .then(Commands.literal("info").executes(AtlasCommand::executeInfo))
                .then(Commands.literal("bench").executes(AtlasCommand::executeBench))
                .then(Commands.literal("validate").executes(AtlasCommand::executeValidate))
                .then(Commands.literal("pregen")
                    .then(Commands.argument("chunks", IntegerArgumentType.integer(64, 50000))
                        .executes(AtlasCommand::executePregen)))
        );
    }

    private static int executeInfo(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeap = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        boolean vectorAvailable = isVectorApiAvailable();

        sendMessage(src, "§6§l[Atlas] §rstatus");
        sendMessage(src, "§7  version:       §f0.1.0-SNAPSHOT (Phase 1)");
        sendMessage(src, "§7  cores:         §f" + cores);
        sendMessage(src, "§7  max heap:      §f" + maxHeap + " MB");
        sendMessage(src, "§7  Vector API:    §f" + (vectorAvailable ? "§aAVAILABLE" : "§cMISSING (add --add-modules=jdk.incubator.vector)"));
        sendMessage(src, "§7  pipeline mode: §fengine + service API (Phase 2 wires ChunkGenerator)");
        sendMessage(src, "§7Try: §e/atlas bench§7 or §e/atlas validate");
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

    private static int executePregen(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int requestedChunks = IntegerArgumentType.getInteger(ctx, "chunks");
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);

        // Round up chunks to a whole number of 8x8 tiles (each tile = 64 chunks).
        int tiles = (requestedChunks + 63) / 64;
        int actualChunks = tiles * 64;

        // Square-ish layout for tiles, centred on player.
        int sideLen = (int) Math.ceil(Math.sqrt(tiles));

        int playerTileX, playerTileZ;
        try {
            var pos = src.getPosition();
            playerTileX = (int) Math.floor(pos.x() / 128.0);
            playerTileZ = (int) Math.floor(pos.z() / 128.0);
        } catch (Throwable t) {
            playerTileX = 0;
            playerTileZ = 0;
        }
        int originTileX = playerTileX - sideLen / 2;
        int originTileZ = playerTileZ - sideLen / 2;

        sendMessage(src, "§6§l[Atlas] §rpregen: " + actualChunks + " chunks ("
            + tiles + " tiles, " + sideLen + "x" + sideLen + " grid) around tile ("
            + playerTileX + "," + playerTileZ + "), parallelism " + parallelism + "...");

        DfcNode tree = buildBenchTree();
        CompiledSampler sampler = JitCompiler.compile(tree, JitOptions.DEFAULT);

        long t0 = System.nanoTime();
        long peakHeapBytes;
        try (DagScheduler sched = new DagScheduler(parallelism, parallelism * 4);
             TilePipeline pipeline = new TilePipeline(0xC0FFEEL, sampler, sched)) {

            CompletableFuture<?>[] futs = new CompletableFuture[tiles];
            int submitted = 0;
            outer:
            for (int dz = 0; dz < sideLen; dz++) {
                for (int dx = 0; dx < sideLen; dx++) {
                    if (submitted >= tiles) break outer;
                    futs[submitted++] = pipeline.generate(
                        new TileCoord(originTileX + dx, originTileZ + dz));
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
            peakHeapBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            for (CompletableFuture<?> f : futs) {
                try { ((Tile) f.get()).close(); } catch (Exception ignored) {}
            }
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        double cps = actualChunks * 1000.0 / Math.max(1, elapsedMs);
        double tilesPerSec = tiles * 1000.0 / Math.max(1, elapsedMs);

        sendMessage(src, "§6§l[Atlas] §rpregen complete:");
        sendMessage(src, "§7  generated:     §a" + actualChunks + " chunks");
        sendMessage(src, "§7  elapsed:       §f" + elapsedMs + " ms");
        sendMessage(src, "§7  throughput:    §a" + String.format("%.1f cps §7(%.1f tiles/sec)", cps, tilesPerSec));
        sendMessage(src, "§7  parallelism:   §f" + parallelism + " threads");
        sendMessage(src, "§7  peak heap:     §f" + (peakHeapBytes / (1024 * 1024)) + " MB");
        sendMessage(src, "§7  centred on:    §ftile (" + playerTileX + ", " + playerTileZ
            + ") = block (" + (playerTileX * 128) + ", " + (playerTileZ * 128) + ")");
        sendMessage(src, "§7  (noise-only — vanilla still owns blocks/biomes/features in the world)");
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
