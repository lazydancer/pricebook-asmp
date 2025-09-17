package com.glumbo.pricebook.scanner;

import com.glumbo.pricebook.GlumboPricebook;
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
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class HttpScanTransport {
    private static final int MAX_ATTEMPTS = 5;
    private static final int PAGE_SIZE = 256;

    private final ModConfig config;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final URI scansEndpoint;

    private final Queue<PendingScan> queue = new ConcurrentLinkedQueue<>();
    private final Set<ChunkCoordinate> serverKnownChunks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean sending = new AtomicBoolean(false);

    private volatile Instant nextAttempt = Instant.EPOCH;

    public HttpScanTransport(ModConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = normalizeBaseUrl(config.apiBaseUrl());
        this.scansEndpoint = URI.create(baseUrl + "/api/scans");
    }

    public void submit(List<ShopScan> scans) {
        scans.stream()
                .map(scan -> {
                    ChunkCoordinate coordinate = ChunkCoordinate.fromScan(scan);
                    boolean empty = scan.shops().isEmpty();
                    if (!empty) {
                        serverKnownChunks.add(coordinate);
                    }
                    return new PendingScan(scan, 0, empty, coordinate);
                })
                .forEach(queue::offer);
        processQueue();
    }

    public void onTransportTick() {
        processQueue();
    }

    public void bootstrap() {
        fetchChunksPage();
    }

    public void clear() {
        queue.clear();
        sending.set(false);
        nextAttempt = Instant.EPOCH;
        serverKnownChunks.clear();
    }

    public boolean shouldTransmitEmpty(String dimension, ChunkPos pos) {
        return serverKnownChunks.contains(new ChunkCoordinate(dimension, pos.x, pos.z));
    }

    private void processQueue() {
        if (Instant.now().isBefore(nextAttempt)) {
            return;
        }

        if (!sending.compareAndSet(false, true)) {
            return;
        }

        PendingScan pending = queue.poll();
        if (pending == null) {
            sending.set(false);
            return;
        }

        sendAsync(pending);
    }

    private void sendAsync(PendingScan pending) {
        ShopScan scan = pending.scan();
        String payload = encodeScan(scan);

        HttpRequest request = HttpRequest.newBuilder(scansEndpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handleResponse(pending, response, throwable));
    }

    private void handleResponse(PendingScan pending, HttpResponse<String> response, Throwable throwable) {
        if (throwable != null) {
            GlumboPricebook.LOGGER.warn("Failed to send scan {}: {}", pending.scan().scanId(), throwable.toString());
            scheduleRetry(pending);
            return;
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            if (pending.empty()) {
                serverKnownChunks.remove(pending.coordinate());
            } else {
                serverKnownChunks.add(pending.coordinate());
            }
            GlumboPricebook.LOGGER.debug("Delivered scan {} with status {}", pending.scan().scanId(), status);
            sending.set(false);
            processQueue();
            return;
        }

        GlumboPricebook.LOGGER.warn("Scan {} rejected with status {} body={} ", pending.scan().scanId(), status, response.body());
        scheduleRetry(pending);
    }

    private void scheduleRetry(PendingScan pending) {
        if (pending.attempt() + 1 >= MAX_ATTEMPTS) {
            GlumboPricebook.LOGGER.error("Exceeded retry attempts for scan {}. Dropping.", pending.scan().scanId());
            sending.set(false);
            processQueue();
            return;
        }

        queue.offer(pending.nextAttempt());
        nextAttempt = Instant.now().plus(config.reconnectBackoff());
        sending.set(false);
        processQueue();
    }

    private String encodeScan(ShopScan scan) {
        JsonObject root = new JsonObject();
        root.addProperty("senderId", scan.senderId());
        root.addProperty("scanId", scan.scanId());
        root.addProperty("dimension", scan.dimension());
        root.addProperty("chunkX", scan.chunkX());
        root.addProperty("chunkZ", scan.chunkZ());
        root.addProperty("scannedAt", scan.scannedAt().toString());

        JsonArray shops = new JsonArray();
        for (ShopObservation observation : scan.shops()) {
            JsonObject shop = new JsonObject();
            shop.addProperty("owner", observation.owner());
            shop.addProperty("item", observation.item());
            shop.addProperty("price", observation.price());
            shop.addProperty("amount", observation.amount());
            shop.addProperty("dimension", observation.dimension());
            shop.addProperty("action", observation.action().apiValue());

            JsonArray position = new JsonArray();
            int[] coords = observation.asPositionArray();
            position.add(coords[0]);
            position.add(coords[1]);
            position.add(coords[2]);
            shop.add("position", position);

            shops.add(shop);
        }

        root.add("shops", shops);
        return root.toString();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        if (trimmed.isEmpty()) {
            trimmed = "http://localhost:49876";
        }

        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("Base URL missing scheme or host");
            }
            return trimmed;
        } catch (IllegalArgumentException ex) {
            GlumboPricebook.LOGGER.error("Invalid API base URL '{}', falling back to default", baseUrl, ex);
            return "http://localhost:49876";
        }
    }

    private void fetchChunksPage() {
        URI uri = URI.create(baseUrl + "/api/chunks");
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .whenComplete((response, throwable) -> handleChunksResponse(offset, response, throwable));
    }

    private void handleChunksResponse(int offset, HttpResponse<String> response, Throwable throwable) {
        if (throwable != null) {
            GlumboPricebook.LOGGER.warn("Failed to fetch chunk bootstrap data: {}", throwable.toString());
            return;
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            GlumboPricebook.LOGGER.warn("Chunk bootstrap request failed with status {} body={}", response.statusCode(), response.body());
            return;
        }

        List<ChunkCoordinate> chunks = parseChunkList(response.body());
        if (chunks.isEmpty()) {
            return;
        }

        for (ChunkCoordinate chunk : chunks) {
            serverKnownChunks.add(chunk);
        }

        if (chunks.size() == PAGE_SIZE) {
            fetchChunksPage(offset + PAGE_SIZE);
        }
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
                    // Skip malformed entries without aborting the entire list.
                }
            }
        } catch (RuntimeException ex) {
            GlumboPricebook.LOGGER.warn("Failed to parse chunk bootstrap payload: {}", ex.toString());
        }
        return result;
    }

    private record PendingScan(ShopScan scan, int attempt, boolean empty, ChunkCoordinate coordinate) {
        PendingScan nextAttempt() {
            return new PendingScan(scan, attempt + 1, empty, coordinate);
        }
    }

    private record ChunkCoordinate(String dimension, int chunkX, int chunkZ) {
        ChunkCoordinate {
            dimension = dimension == null ? "" : dimension.toLowerCase();
        }

        static ChunkCoordinate fromScan(ShopScan scan) {
            return new ChunkCoordinate(scan.dimension(), scan.chunkX(), scan.chunkZ());
        }
    }
}
