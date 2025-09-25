package com.asmp.pricebook.util;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Maintains a single shared {@link HttpClient} for outbound requests so we do not
 * spin up redundant thread pools per service.
 */
public final class HttpClients {
    private static final HttpClient SHARED = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private HttpClients() {
    }

    public static HttpClient shared() {
        return SHARED;
    }
}

