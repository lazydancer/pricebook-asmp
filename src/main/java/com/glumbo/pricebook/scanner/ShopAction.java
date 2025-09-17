package com.glumbo.pricebook.scanner;

import java.util.Locale;
import java.util.Optional;

public enum ShopAction {
    SELL("sell"),
    BUY("buy");

    private final String apiValue;

    ShopAction(String apiValue) {
        this.apiValue = apiValue;
    }

    public String apiValue() {
        return apiValue;
    }

    public static Optional<ShopAction> fromStatusLine(String statusLine) {
        if (statusLine == null) {
            return Optional.empty();
        }
        String normalized = statusLine.toLowerCase(Locale.ROOT);
        if (normalized.contains("selling")) {
            return Optional.of(SELL);
        }
        if (normalized.contains("buying")) {
            return Optional.of(BUY);
        }
        return Optional.empty();
    }
}
