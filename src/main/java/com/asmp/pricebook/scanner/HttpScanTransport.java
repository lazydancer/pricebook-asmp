package com.asmp.pricebook.scanner;

import com.asmp.pricebook.config.ModConfig;
import com.asmp.pricebook.util.HttpClients;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class HttpScanTransport {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final URI scanEndpoint;
    private final URI waystoneEndpoint;
    private final Set<ChunkCoordinate> serverKnownChunks = ConcurrentHashMap.newKeySet();

    public HttpScanTransport(ModConfig config) {
        Objects.requireNonNull(config, "config");
        this.httpClient = HttpClients.shared();
        this.baseUrl = config.apiBaseUrl();
        this.scanEndpoint = URI.create(baseUrl + "/v1/scan");
        this.waystoneEndpoint = URI.create(baseUrl + "/v1/scan-waystone");
    }

    public void sendScan(String senderId, String dimension, ChunkPos pos,
                         List<ShopSignParser.ShopEntry> shops, List<BlockPos> waystones) {
        ChunkCoordinate coordinate = new ChunkCoordinate(dimension, pos.x, pos.z);
        boolean empty = shops.isEmpty() && waystones.isEmpty();
        if (!empty) {
            serverKnownChunks.add(coordinate);
        }

        String payload = encodePayload(senderId, dimension, pos, shops, waystones);

        HttpRequest request = HttpRequest.newBuilder(scanEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handleSendResult(coordinate, empty, response, throwable));
    }

    public void sendWaystoneScan(String senderId, String dimension, ChunkPos chunkPos, BlockPos position,
                                 String name, String owner) {
        JsonObject root = new JsonObject();
        root.addProperty("senderId", senderId);
        root.addProperty("dimension", dimension);
        root.addProperty("chunkX", chunkPos.x);
        root.addProperty("chunkZ", chunkPos.z);
        root.addProperty("name", name);
        root.addProperty("owner", owner);

        JsonArray coords = new JsonArray();
        coords.add(position.getX());
        coords.add(position.getY());
        coords.add(position.getZ());
        root.add("position", coords);

        HttpRequest request = HttpRequest.newBuilder(waystoneEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(root.toString(), StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding());
    }

    public void bootstrap() {
        fetchChunksPage();
    }

    public void clear() {
        serverKnownChunks.clear();
    }

    public boolean shouldTransmitEmpty(String dimension, ChunkPos pos) {
        return serverKnownChunks.contains(new ChunkCoordinate(dimension, pos.x, pos.z));
    }

    private void handleSendResult(ChunkCoordinate coordinate, boolean empty,
                                  HttpResponse<String> response, Throwable throwable) {
        if (throwable != null) {
            return;
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            if (empty) {
                serverKnownChunks.remove(coordinate);
            } else {
                serverKnownChunks.add(coordinate);
            }
            return;
        }

    }

    private String encodePayload(String senderId, String dimension, ChunkPos pos,
                                  List<ShopSignParser.ShopEntry> shops, List<BlockPos> waystones) {
        JsonObject root = new JsonObject();
        root.addProperty("senderId", senderId);
        root.addProperty("dimension", dimension);
        root.addProperty("chunkX", pos.x);
        root.addProperty("chunkZ", pos.z);

        JsonArray shopsJson = new JsonArray();
        for (ShopSignParser.ShopEntry entry : shops) {
            JsonObject shop = new JsonObject();
            shop.addProperty("owner", entry.owner());
            shop.addProperty("item", entry.item());
            shop.addProperty("price", entry.price());
            shop.addProperty("amount", entry.amount());
            shop.addProperty("dimension", dimension);
            shop.addProperty("action", entry.action());

            JsonArray position = new JsonArray();
            position.add(entry.position().getX());
            position.add(entry.position().getY());
            position.add(entry.position().getZ());
            shop.add("position", position);

            shopsJson.add(shop);
        }

        root.add("shops", shopsJson);

        JsonArray waystonesJson = new JsonArray();
        for (BlockPos waypoint : waystones) {
            JsonObject element = new JsonObject();
            JsonArray position = new JsonArray();
            position.add(waypoint.getX());
            position.add(waypoint.getY());
            position.add(waypoint.getZ());
            element.add("position", position);
            waystonesJson.add(element);
        }
        root.add("waystones", waystonesJson);
        return root.toString();
    }

    private void fetchChunksPage() {
        URI uri = URI.create(baseUrl + "/v1/chunks");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handleChunksResponse(response, throwable));
    }

    private void handleChunksResponse(HttpResponse<String> response, Throwable throwable) {
        if (throwable != null) {
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return;
        }

        List<ChunkCoordinate> chunks = parseChunkList(response.body());
        if (chunks.isEmpty()) {
            return;
        }

        serverKnownChunks.addAll(chunks);
    }

    private List<ChunkCoordinate> parseChunkList(String body) {
        List<ChunkCoordinate> result = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return result;
            }

            JsonObject root = parsed.getAsJsonObject();
            JsonArray chunks = root.getAsJsonArray("chunks");
            if (chunks == null) {
                return result;
            }

            for (JsonElement element : chunks) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                JsonElement dim = obj.get("dimension");
                JsonElement chunkX = obj.get("chunkX");
                JsonElement chunkZ = obj.get("chunkZ");
                if (dim == null || chunkX == null || chunkZ == null) {
                    continue;
                }
                try {
                    result.add(new ChunkCoordinate(dim.getAsString(), chunkX.getAsInt(), chunkZ.getAsInt()));
                } catch (RuntimeException ignored) {
                    // skip malformed entry
                }
            }
        } catch (RuntimeException ex) {
            // ignore malformed payloads
        }
        return result;
    }


    private record ChunkCoordinate(String dimension, int chunkX, int chunkZ) {
        ChunkCoordinate {
            dimension = dimension == null ? "" : dimension.toLowerCase();
        }
    }
}
