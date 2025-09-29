package com.asmp.pricebook.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class ModVersionChecker {
    private static final Logger LOGGER = Loggers.APP;

    private ModVersionChecker() {
    }

    public static CompletableFuture<Result> checkAsync(String baseUrl, String currentVersion) {
        String url = normalizeEndpoint(baseUrl);
        if (url.isEmpty()) {
            LOGGER.warn("Cannot check mod version: invalid base URL");
            return CompletableFuture.completedFuture(Result.compatibleResult());
        }

        LOGGER.debug("Checking mod version, current={}, endpoint={}", currentVersion, url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/json")
                .build();

        return HttpClients.shared()
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> parseResponse(response, currentVersion))
                .exceptionally(ex -> {
                    LOGGER.warn("Version check failed: {}", ex.getMessage());
                    return Result.compatibleResult();
                });
    }

    private static Result parseResponse(HttpResponse<String> response, String currentVersion) {
        if (response == null) {
            return Result.compatibleResult();
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warn("Version check returned status {}", response.statusCode());
            return Result.compatibleResult();
        }

        String body = response.body();
        if (body == null || body.isBlank()) {
            return Result.compatibleResult();
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(body);
        } catch (RuntimeException ex) {
            return Result.compatibleResult();
        }
        if (!parsed.isJsonObject()) {
            return Result.compatibleResult();
        }

        JsonObject obj = parsed.getAsJsonObject();
        JsonElement minElement = obj.get("min_version");
        if (minElement == null || minElement.isJsonNull()) {
            return Result.compatibleResult();
        }

        String minVersion;
        try {
            minVersion = minElement.getAsString();
        } catch (RuntimeException ex) {
            return Result.compatibleResult();
        }
        if (minVersion == null || minVersion.isBlank()) {
            return Result.compatibleResult();
        }

        String current = currentVersion == null ? "0" : currentVersion.trim();
        if (compareVersions(current, minVersion) < 0) {
            LOGGER.warn("Mod version {} is outdated, minimum required is {}", current, minVersion);
            return Result.outdated(minVersion);
        }
        LOGGER.info("Mod version {} is compatible (minimum: {})", current, minVersion);
        return Result.compatibleResult();
    }

    private static String normalizeEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "";
        }
        String trimmed = baseUrl.trim();
        if (!trimmed.endsWith("/")) {
            trimmed += "/";
        }
        return trimmed + "v1/mod-version";
    }

    private static int compareVersions(String current, String minimum) {
        String[] currentParts = current.split("[.-]");
        String[] minimumParts = minimum.split("[.-]");
        int length = Math.max(currentParts.length, minimumParts.length);
        for (int i = 0; i < length; i++) {
            int currentValue = i < currentParts.length ? parseIntSafe(currentParts[i]) : 0;
            int minimumValue = i < minimumParts.length ? parseIntSafe(minimumParts[i]) : 0;
            if (currentValue != minimumValue) {
                return Integer.compare(currentValue, minimumValue);
            }
        }
        return 0;
    }

    private static int parseIntSafe(String part) {
        try {
            return Integer.parseInt(part);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    public record Result(boolean compatible, String requiredVersion) {
        public static Result compatibleResult() {
            return new Result(true, "");
        }

        public static Result outdated(String requiredVersion) {
            return new Result(false, requiredVersion == null ? "" : requiredVersion);
        }
    }
}
