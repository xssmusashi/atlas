package dev.xssmusashi.atlas.mc.command;

import com.mojang.brigadier.CommandDispatcher;
import dev.xssmusashi.atlas.core.dfc.DfcNode;
import dev.xssmusashi.atlas.core.jit.CompiledSampler;
import dev.xssmusashi.atlas.core.jit.JitCompiler;
import dev.xssmusashi.atlas.core.jit.JitOptions;
import dev.xssmusashi.atlas.core.pool.DagScheduler;
import dev.xssmusashi.atlas.core.tile.Tile;
import dev.xssmusashi.atlas.core.tile.TileCoord;
import dev.xssmusashi.atlas.core.tile.TilePipeline;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * In-game commands for inspecting and benchmarking Atlas.
 * <ul>
 *   <li>{@code /atlas info} — version, JIT mode, parallelism, current state</li>
 *   <li>{@code /atlas bench} — run a small tile-pipeline benchmark and report cps in chat</li>
 *   <li>{@code /atlas validate} — verify JIT vs interpreter on a random tree (correctness check)</li>
 * </ul>
 * <p>
 * Commands run on the server thread but kick benchmark work to a private DAG scheduler
 * to avoid stalling the main loop.
 */
public final class AtlasCommand {

    private AtlasCommand() {}

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            registerCommands(dispatcher);
        });
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("atlas")
                .requires(source -> source.hasPermissionLevel(2))
                .then(CommandManager.literal("info").executes(AtlasCommand::executeInfo))
                .then(CommandManager.literal("bench").executes(AtlasCommand::executeBench))
                .then(CommandManager.literal("validate").executes(AtlasCommand::executeValidate))
        );
    }

    private static int executeInfo(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
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

    private static int executeBench(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        int parallelism = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int tilesToGen = 8;

        sendMessage(src, "§6§l[Atlas] §rrunning bench: " + tilesToGen + " tiles, parallelism " + parallelism + "...");

        // Build a realistic worldgen-shaped tree (continentalness + erosion + Y gradient).
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

            // Each tile = 64 chunks (8x8).
            int chunks = tilesToGen * 64;
            double cps = chunks * 1000.0 / Math.max(1, elapsedMs);

            sendMessage(src, "§6§l[Atlas] §rresult:");
            sendMessage(src, "§7  generated:     §f" + chunks + " chunks (" + tilesToGen + " tiles)");
            sendMessage(src, "§7  elapsed:       §f" + elapsedMs + " ms");
            sendMessage(src, "§7  throughput:    §a" + String.format("%.1f cps", cps));
            sendMessage(src, "§7  parallelism:   §f" + parallelism + " threads");
            sendMessage(src, "§7  (noise-only stage; full pipeline arrives in Phase 2)");

            // Cleanup — tiles allocated by the pipeline must be closed.
            for (CompletableFuture<?> f : futs) {
                try { ((Tile) f.get()).close(); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    private static int executeValidate(com.mojang.brigadier.context.CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
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

    private static void sendMessage(ServerCommandSource src, String message) {
        src.sendFeedback(() -> Text.literal(message), false);
    }
}
