package com.glumbo.pricebook.scanner;

import com.glumbo.pricebook.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.ChunkPos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HttpScanTransport {
    private final HttpClient httpClient;
    private final String baseUrl;
    private final URI scansEndpoint;
    private final Set<ChunkCoordinate> serverKnownChunks = ConcurrentHashMap.newKeySet();

    public HttpScanTransport(ModConfig config) {
        Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = config.apiBaseUrl();
        this.scansEndpoint = URI.create(baseUrl + "/api/scans");
    }

    public void sendScan(String senderId, String dimension, ChunkPos pos, List<ShopSignParser.ShopEntry> shops) {
        ChunkCoordinate coordinate = new ChunkCoordinate(dimension, pos.x, pos.z);
        boolean empty = shops.isEmpty();
        if (!empty) {
            serverKnownChunks.add(coordinate);
        }

        String scanId = UUID.randomUUID().toString();
        String payload = encodePayload(senderId, scanId, dimension, pos, shops);

        HttpRequest request = HttpRequest.newBuilder(scansEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handleSendResult(scanId, coordinate, empty, response, throwable));
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

    private void handleSendResult(String scanId, ChunkCoordinate coordinate, boolean empty,
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

    private String encodePayload(String senderId, String scanId, String dimension, ChunkPos pos,
                                  List<ShopSignParser.ShopEntry> shops) {
        JsonObject root = new JsonObject();
        root.addProperty("senderId", senderId);
        root.addProperty("scanId", scanId);
        root.addProperty("dimension", dimension);
        root.addProperty("chunkX", pos.x);
        root.addProperty("chunkZ", pos.z);
        root.addProperty("scannedAt", Instant.now().toString());

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
        return root.toString();
    }

    private void fetchChunksPage() {
        URI uri = URI.create(baseUrl + "/api/chunks");
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
