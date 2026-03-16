package me.infin1te.aei;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEKey;
import appeng.blockentity.networking.WirelessAccessPointBlockEntity;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

public final class Ae2GridApiServer {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BIND_HOST = "0.0.0.0";

    private static HttpServer server;
    private static ServerContext context;

    private Ae2GridApiServer() {
    }

    public static synchronized StartResult start(MinecraftServer minecraftServer, int port) {
        try {
            if (server != null) {
                stop();
            }

            context = new ServerContext(minecraftServer, port);
            server = HttpServer.create(new InetSocketAddress(BIND_HOST, port), 0);
            server.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "AEI-AE2-API");
                t.setDaemon(true);
                return t;
            }));

            server.createContext("/health", Ae2GridApiServer::handleHealth);
            server.createContext("/api/ae2/grid", Ae2GridApiServer::handleGrid);
            server.createContext("/", Ae2GridApiServer::handleRoot);
            server.start();

            LOGGER.info("AEI: AE2 grid API server started on http://{}:{}", BIND_HOST, port);
            return new StartResult(true, "http://" + BIND_HOST + ":" + port + "/api/ae2/grid");
        } catch (IOException e) {
            LOGGER.error("AEI: Failed to start AE2 grid API server", e);
            context = null;
            server = null;
            return new StartResult(false, "Failed to bind to port " + port + ".");
        }
    }

    public static synchronized boolean stop() {
        if (server == null) {
            return false;
        }

        server.stop(0);
        server = null;
        context = null;
        LOGGER.info("AEI: AE2 grid API server stopped");
        return true;
    }

    public static synchronized boolean isRunning() {
        return server != null;
    }

    @Nullable
    public static synchronized String getServerUrl() {
        if (context == null) {
            return null;
        }
        return "http://" + BIND_HOST + ":" + context.port() + "/api/ae2/grid";
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (handleCorsAndMethod(exchange, "GET")) {
                return;
            }

            JsonObject json = new JsonObject();
            json.addProperty("name", "AEI AE2 Grid API");
            json.addProperty("health", "/health");
            json.addProperty("grid", "/api/ae2/grid");
            writeJson(exchange, 200, json);
        }
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (handleCorsAndMethod(exchange, "GET")) {
                return;
            }

            JsonObject json = new JsonObject();
            json.addProperty("ok", true);
            json.addProperty("running", isRunning());
            writeJson(exchange, 200, json);
        }
    }

    private static void handleGrid(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (handleCorsAndMethod(exchange, "GET")) {
                return;
            }

            ServerContext ctx;
            synchronized (Ae2GridApiServer.class) {
                ctx = context;
            }

            if (ctx == null) {
                JsonObject err = new JsonObject();
                err.addProperty("error", "Server is not running.");
                writeJson(exchange, 503, err);
                return;
            }

            JsonObject payload = buildSnapshot(ctx);
            int status = payload.has("error") ? 500 : 200;
            writeJson(exchange, status, payload);
        }
    }

    private static JsonObject buildSnapshot(ServerContext ctx) {
        if (ctx.server().isSameThread()) {
            return buildSnapshotOnServerThread(ctx);
        }

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        ctx.server().execute(() -> {
            try {
                future.complete(buildSnapshotOnServerThread(ctx));
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });

        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            JsonObject root = new JsonObject();
            root.addProperty("source", "AEI");
            root.addProperty("discovery", "wap_scan");
            root.addProperty("error", "Interrupted while collecting AE2 grid snapshot.");
            return root;
        } catch (TimeoutException e) {
            JsonObject root = new JsonObject();
            root.addProperty("source", "AEI");
            root.addProperty("discovery", "wap_scan");
            root.addProperty("error", "Timed out while collecting AE2 grid snapshot.");
            return root;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOGGER.error("AEI: Failed to collect AE2 grid snapshot", cause);
            JsonObject root = new JsonObject();
            root.addProperty("source", "AEI");
            root.addProperty("discovery", "wap_scan");
            root.addProperty("error", cause.getMessage() == null ? "Unknown error" : cause.getMessage());
            return root;
        }
    }

    private static JsonObject buildSnapshotOnServerThread(ServerContext ctx) {
        JsonObject root = new JsonObject();
        root.addProperty("source", "AEI");
        root.addProperty("discovery", "wap_scan");

        try {
            Set<IGrid> uniqueGrids = Collections.newSetFromMap(new IdentityHashMap<>());
            int totalWapsFound = 0;
            int totalWapsConnected = 0;
            for (ServerLevel level : ctx.server().getAllLevels()) {
                LevelScanResult scan = findWapGrids(level);
                uniqueGrids.addAll(scan.grids());
                totalWapsFound += scan.wapsFound();
                totalWapsConnected += scan.wapsConnected();
            }

            root.addProperty("gridCount", uniqueGrids.size());
            root.addProperty("wapsFound", totalWapsFound);
            root.addProperty("wapsConnected", totalWapsConnected);
            JsonArray gridsJson = new JsonArray();
            int index = 0;
            for (IGrid grid : uniqueGrids) {
                gridsJson.add(serializeGrid(index++, grid));
            }
            root.add("grids", gridsJson);
            return root;
        } catch (RuntimeException e) {
            LOGGER.error("AEI: Failed to collect AE2 grid snapshot", e);
            root.addProperty("error", e.getMessage() == null ? "Unknown error" : e.getMessage());
            return root;
        }
    }

    private static LevelScanResult findWapGrids(ServerLevel level) {
        Set<IGrid> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Long> scannedChunks = new HashSet<>();
        int[] counts = new int[2];
        int radius = Math.max(2, level.getServer().getPlayerList().getViewDistance() + 2);

        level.players().forEach(player -> {
            ChunkPos center = player.chunkPosition();
            scanChunkSquare(level, center.x, center.z, radius, scannedChunks, unique, counts);
        });

        ChunkPos spawn = new ChunkPos(Objects.requireNonNull(level.getSharedSpawnPos()));
        scanChunkSquare(level, spawn.x, spawn.z, 4, scannedChunks, unique, counts);

        return new LevelScanResult(unique, counts[0], counts[1]);
    }

    private static void scanChunkSquare(
            ServerLevel level,
            int centerChunkX,
            int centerChunkZ,
            int radius,
            Set<Long> scannedChunks,
            Set<IGrid> grids,
            int[] counts
    ) {
        for (int x = centerChunkX - radius; x <= centerChunkX + radius; x++) {
            for (int z = centerChunkZ - radius; z <= centerChunkZ + radius; z++) {
                long key = ChunkPos.asLong(x, z);
                if (!scannedChunks.add(key)) {
                    continue;
                }

                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) {
                    continue;
                }

                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof WirelessAccessPointBlockEntity wap) {
                        counts[0]++;
                        IGrid grid = resolveGridFromWap(wap);
                        if (grid != null) {
                            counts[1]++;
                            grids.add(grid);
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private static IGrid resolveGridFromWap(WirelessAccessPointBlockEntity wap) {
        IGrid grid = wap.getGrid();
        if (grid != null) {
            return grid;
        }

        // Fallback for transitional node states while the block entity initializes.
        IGridNode node = wap.getMainNode().getNode();
        return node == null ? null : node.getGrid();
    }

    private static JsonObject serializeGrid(int index, IGrid grid) {
        JsonObject json = new JsonObject();
        json.addProperty("index", index);
        json.addProperty("nodeCount", grid.size());

        JsonArray stacks = new JsonArray();
        long totalAmount = 0;

        for (Object2LongMap.Entry<AEKey> entry : grid.getStorageService().getCachedInventory()) {
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (key == null || amount <= 0) {
                continue;
            }

            JsonObject stackJson = new JsonObject();
            stackJson.addProperty("id", String.valueOf(key.getId()));
            stackJson.addProperty("type", String.valueOf(key.getType()));
            stackJson.addProperty("name", componentToString(key.getDisplayName()));
            stackJson.addProperty("amount", amount);
            stacks.add(stackJson);
            totalAmount += amount;
        }

        json.addProperty("stackTypes", stacks.size());
        json.addProperty("totalAmount", totalAmount);
        json.add("stacks", stacks);
        return json;
    }

    private static String componentToString(@Nullable Object component) {
        if (component instanceof Component c) {
            return c.getString();
        }
        return component == null ? "" : String.valueOf(component);
    }

    private static boolean handleCorsAndMethod(HttpExchange exchange, String allowedMethod) throws IOException {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return true;
        }

        if (!allowedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Method not allowed");
            writeJson(exchange, 405, error);
            return true;
        }

        return false;
    }

    private static void writeJson(HttpExchange exchange, int statusCode, JsonObject body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private record ServerContext(MinecraftServer server, int port) {
    }

    private record LevelScanResult(Set<IGrid> grids, int wapsFound, int wapsConnected) {
    }

    public record StartResult(boolean success, String message) {
    }
}
