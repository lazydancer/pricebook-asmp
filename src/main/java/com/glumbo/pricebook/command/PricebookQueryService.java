package com.glumbo.pricebook.command;

import com.glumbo.pricebook.config.ModConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.math.BlockPos;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PricebookQueryService {
    private final HttpClient httpClient;
    private final String baseUrl;

    public PricebookQueryService(ModConfig config) {
        Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.baseUrl = config.apiBaseUrl();
    }

    public CompletableFuture<ItemLookupResult> lookup(String itemName) {
        String trimmed = itemName == null ? "" : itemName.trim();
        if (trimmed.isEmpty()) {
            return CompletableFuture.completedFuture(ItemLookupResult.error("Item name required."));
        }

        String encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8);
        URI uri = URI.create(baseUrl + "/v1/item?item=" + encoded);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseResponse)
                .exceptionally(throwable -> ItemLookupResult.error("Failed to reach pricebook service."));
    }

    public CompletableFuture<List<String>> fetchCatalog() {
        URI uri = URI.create(baseUrl + "/v1/items");

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(this::parseCatalog)
                .exceptionally(throwable -> Collections.emptyList());
    }

    private ItemLookupResult parseResponse(HttpResponse<String> response) {
        if (response == null) {
            return ItemLookupResult.error("No response from pricebook service.");
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return ItemLookupResult.error("Pricebook service returned status " + status + ".");
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return ItemLookupResult.error("Pricebook service returned empty body.");
        }

        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return ItemLookupResult.error("Malformed pricebook payload.");
            }

            JsonObject root = parsed.getAsJsonObject();
            boolean ok = root.has("ok") && root.get("ok").getAsBoolean();
            if (!ok) {
                return ItemLookupResult.error("Item not found. No buyers or sellers yet.");
            }

            String item = getString(root, "item");
            Instant refreshedAt = parseInstant(getString(root, "refreshedAt"));

            List<Listing> sellers = parseListings(root.getAsJsonArray("topSellers"));
            List<Listing> buyers = parseListings(root.getAsJsonArray("topBuyers"));

            if ((item == null || item.isBlank()) && sellers.isEmpty() && buyers.isEmpty()) {
                return ItemLookupResult.error("Item not found. No buyers or sellers yet.");
            }

            ItemInfo info = new ItemInfo(item, refreshedAt, sellers, buyers);
            return ItemLookupResult.success(info);
        } catch (RuntimeException ex) {
            return ItemLookupResult.error("Item not found. No buyers or sellers yet.");
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || key == null) {
            return "";
        }
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) {
            return "";
        }
        try {
            return element.getAsString();
        } catch (RuntimeException ignored) {
            return element.toString();
        }
    }

    private List<Listing> parseListings(JsonArray array) {
        if (array == null) {
            return Collections.emptyList();
        }

        List<Listing> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();
            try {
                String owner = getString(obj, "owner");
                double price = safeDouble(obj, "price");
                int amount = safeInt(obj, "amount");
                String dimension = getString(obj, "dimension");
                Instant lastSeenAt = parseInstant(getString(obj, "lastSeenAt"));

                BlockPos position = null;
                JsonArray coords = obj.getAsJsonArray("coords");
                if (coords != null && coords.size() == 3) {
                    try {
                        int x = coords.get(0).getAsInt();
                        int y = coords.get(1).getAsInt();
                        int z = coords.get(2).getAsInt();
                        position = new BlockPos(x, y, z);
                    } catch (RuntimeException ignored) {
                        position = null;
                    }
                }

                WaystoneReference waystone = parseWaystone(obj.get("nearestWaystone"));
                result.add(new Listing(owner, price, amount, position, dimension, lastSeenAt, waystone));
            } catch (RuntimeException ignored) {
                // Skip malformed listing entry
            }
        }
        return result;
    }

    private double safeDouble(JsonObject obj, String key) {
        try {
            JsonElement element = obj.get(key);
            if (element == null || element.isJsonNull()) {
                return 0;
            }
            return element.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private WaystoneReference parseWaystone(JsonElement element) {
        if (element == null || element.isJsonNull() || !element.isJsonObject()) {
            return null;
        }
        JsonObject obj = element.getAsJsonObject();
        String name = getString(obj, "name");
        int distanceSq = safeInt(obj, "distanceSq");

        JsonArray posArray = obj.getAsJsonArray("position");
        if (posArray == null || posArray.size() != 3) {
            return null;
        }
        try {
            int x = posArray.get(0).getAsInt();
            int y = posArray.get(1).getAsInt();
            int z = posArray.get(2).getAsInt();
            return new WaystoneReference(name, new BlockPos(x, y, z), distanceSq);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private int safeInt(JsonObject obj, String key) {
        try {
            JsonElement element = obj.get(key);
            if (element == null || element.isJsonNull()) {
                return 0;
            }
            return element.getAsInt();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private List<String> parseCatalog(HttpResponse<String> response) {
        if (response == null) {
            return Collections.emptyList();
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            return Collections.emptyList();
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return Collections.emptyList();
            }

            JsonObject obj = parsed.getAsJsonObject();
            if (!obj.has("ok") || !obj.get("ok").getAsBoolean()) {
                return Collections.emptyList();
            }

            JsonArray items = obj.getAsJsonArray("items");
            if (items == null) {
                return Collections.emptyList();
            }

            for (JsonElement element : items) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject item = element.getAsJsonObject();
                JsonElement nameElement = item.get("name");
                if (nameElement == null) {
                    continue;
                }
                String name = nameElement.getAsString();
                if (name == null) {
                    continue;
                }
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        } catch (RuntimeException ignored) {
            return Collections.emptyList();
        }
        return result;
    }

    private Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            return Instant.EPOCH;
        }
    }

    public record ItemLookupResult(ItemInfo info, String error) {
        public static ItemLookupResult success(ItemInfo info) {
            return new ItemLookupResult(info, null);
        }

        public static ItemLookupResult error(String message) {
            return new ItemLookupResult(null, message);
        }

        public boolean isSuccess() {
            return info != null;
        }
    }

    public record ItemInfo(String itemName, Instant refreshedAt, List<Listing> topSellers,
                           List<Listing> topBuyers) {
    }

    public record Listing(String owner, double price, int amount, BlockPos position,
                          String dimension, Instant lastSeenAt, WaystoneReference nearestWaystone) {
    }

    public record WaystoneReference(String name, BlockPos position, int distanceSq) {
    }
}
