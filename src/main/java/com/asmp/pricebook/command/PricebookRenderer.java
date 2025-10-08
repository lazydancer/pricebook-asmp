package com.asmp.pricebook.command;

import com.asmp.pricebook.command.PricebookQueryService.ItemInfo;
import com.asmp.pricebook.command.PricebookQueryService.ItemLookupResult;
import com.asmp.pricebook.command.PricebookQueryService.Listing;
import com.asmp.pricebook.command.PricebookQueryService.PriceHistoryResult;
import com.asmp.pricebook.command.PricebookQueryService.WaystoneReference;
import com.asmp.pricebook.util.Dimensions;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PricebookRenderer {
    private static final int MAX_LISTINGS_DISPLAYED = 3;
    private static final int STALENESS_THRESHOLD_MINUTES = 60 * 24;
    private static final String WAYPOINT_COMMAND_NAME = "pricebook_waypoint";
    private static final double PRICE_DELTA_EPSILON = 0.0001;

    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final Text PREFIX = Text.literal("[Pricebook]").formatted(Formatting.AQUA);
    private static final Text SEPARATOR = Text.literal(" · ").formatted(Formatting.DARK_GRAY);
    private static final DateTimeFormatter HISTORY_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);

    static {
        NUMBER_FORMAT.setGroupingUsed(true);
    }

    private PricebookRenderer() {
    }

    public static void deliverResult(ClientPlayerEntity playerRef, ItemLookupResult result) {
        ClientPlayerEntity player = validatePlayer(playerRef);
        if (player == null) {
            return;
        }

        if (result == null) {
            player.sendMessage(prefixed("No response.", Formatting.RED), false);
            return;
        }

        if (!result.isSuccess()) {
            String message = Objects.requireNonNullElse(result.error(), "Unknown error.");
            player.sendMessage(prefixed(message, Formatting.RED), false);
            return;
        }

        ItemInfo info = result.info();
        if (info == null) {
            player.sendMessage(prefixed("Unknown error.", Formatting.RED), false);
            return;
        }

        String itemName = toTitleCase(info.itemName() == null || info.itemName().isBlank() ? "Unknown item" : info.itemName());

        MutableText header = Text.literal("┌ Pricebook ▸ ").formatted(Formatting.AQUA)
                .append(Text.literal(itemName).formatted(Formatting.AQUA));
        player.sendMessage(header, false);

        List<Listing> sellers = info.topSellers();
        List<Listing> buyers = info.topBuyers();

        boolean noSellers = sellers == null || sellers.isEmpty();
        boolean noBuyers = buyers == null || buyers.isEmpty();

        if (noSellers && noBuyers) {
            player.sendMessage(linePrefix().append(Text.literal("No buyers or sellers yet.").formatted(Formatting.GRAY)), false);
            return;
        }

        DecimalFormat priceFormatter = createPriceFormatter(sellers, buyers);

        if (!noSellers) {
            sendList(player, null, sellers, priceFormatter);
        }

        if (!noBuyers) {
            sendList(player, "Buyers", buyers, priceFormatter);
        }

        MutableText historyLink = linePrefix()
                .append(Text.literal("[View Price History]")
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/pricebook_history " + info.itemName()))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to view price history")))));
        player.sendMessage(historyLink, false);
    }

    public static void deliverHistoryResult(ClientPlayerEntity playerRef, PriceHistoryResult result) {
        ClientPlayerEntity player = validatePlayer(playerRef);
        if (player == null) {
            return;
        }

        if (result == null) {
            player.sendMessage(prefixed("No response.", Formatting.RED), false);
            return;
        }

        if (!result.isSuccess()) {
            String message = Objects.requireNonNullElse(result.error(), "Unknown error.");
            player.sendMessage(prefixed(message, Formatting.RED), false);
            return;
        }

        PricebookQueryService.PriceHistory history = result.history();
        if (history == null) {
            player.sendMessage(prefixed("Unknown error.", Formatting.RED), false);
            return;
        }

        String itemName = toTitleCase(history.itemName() == null || history.itemName().isBlank() ? "Unknown item" : history.itemName());

        MutableText header = Text.literal("┌ Pricebook History ▸ ").formatted(Formatting.AQUA)
                .append(Text.literal(itemName).formatted(Formatting.AQUA));
        player.sendMessage(header, false);

        List<PricebookQueryService.HistoryDay> historyDays = history.history();
        if (historyDays == null || historyDays.isEmpty()) {
            player.sendMessage(Text.literal("No price history available.").formatted(Formatting.GRAY), false);
            return;
        }

        List<PricebookQueryService.HistoryDay> sanitizedDays = sanitizeHistoryDays(historyDays);
        if (sanitizedDays.isEmpty()) {
            player.sendMessage(Text.literal("No price history available.").formatted(Formatting.GRAY), false);
            return;
        }

        DecimalFormat priceFormatter = createPriceFormatterForHistory(sanitizedDays);
        HistoryInsights insights = analyzeHistory(sanitizedDays);
        if (insights == null) {
            player.sendMessage(Text.literal("No price history available.").formatted(Formatting.GRAY), false);
            return;
        }

        List<PricebookQueryService.HistoryDay> orderedDays = insights.days();
        int size = orderedDays.size();
        for (int i = 0; i < size; i++) {
            PricebookQueryService.HistoryDay day = orderedDays.get(i);
            PricebookQueryService.HistoryDay previous = (i + 1) < size ? orderedDays.get(i + 1) : null;
            boolean isLatest = i == 0;
            MutableText row = buildHistoryRow(day, previous, priceFormatter, insights, isLatest);
            player.sendMessage(row, false);
        }
    }

    private static List<PricebookQueryService.HistoryDay> sanitizeHistoryDays(List<PricebookQueryService.HistoryDay> historyDays) {
        List<PricebookQueryService.HistoryDay> filtered = new ArrayList<>();
        if (historyDays == null) {
            return filtered;
        }

        for (PricebookQueryService.HistoryDay day : historyDays) {
            if (day == null) {
                continue;
            }
            double price = day.lowestPrice();
            if (Double.isNaN(price) || price <= 0) {
                continue;
            }
            filtered.add(day);
        }

        filtered.sort((first, second) -> compareHistoryDates(second, first));
        return filtered;
    }

    private static HistoryInsights analyzeHistory(List<PricebookQueryService.HistoryDay> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }

        double minPrice = Double.POSITIVE_INFINITY;
        double maxPrice = Double.NEGATIVE_INFINITY;
        PricebookQueryService.HistoryDay minDay = null;
        PricebookQueryService.HistoryDay maxDay = null;

        for (PricebookQueryService.HistoryDay day : days) {
            double price = day.lowestPrice();

            if (price < minPrice) {
                minPrice = price;
                minDay = day;
            }

            if (price > maxPrice) {
                maxPrice = price;
                maxDay = day;
            }
        }

        boolean hasRange = minDay != null && maxDay != null && Math.abs(maxPrice - minPrice) >= PRICE_DELTA_EPSILON;
        return new HistoryInsights(days,
                hasRange ? minDay : null,
                hasRange ? maxDay : null);
    }

    private static MutableText buildHistoryRow(PricebookQueryService.HistoryDay day,
                                               PricebookQueryService.HistoryDay previous,
                                               DecimalFormat priceFormatter,
                                               HistoryInsights insights,
                                               boolean isLatest) {
        String label = isLatest ? "Latest" : formatHistoryDate(day.date());
        MutableText row = linePrefix()
                .append(Text.literal(label).formatted(Formatting.GRAY))
                .append(Text.literal(" ▸ ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("☆").formatted(Formatting.GRAY));

        Formatting priceColor = Formatting.AQUA;
        if (insights.highest() != null && insights.highest().equals(day)) {
            priceColor = Formatting.GOLD;
        } else if (insights.lowest() != null && insights.lowest().equals(day)) {
            priceColor = Formatting.GREEN;
        }

        row.append(Text.literal(priceFormatter.format(day.lowestPrice())).formatted(priceColor));

        if (previous != null) {
            row.append(buildPriceDeltaText(day.lowestPrice() - previous.lowestPrice()));
        } else {
            row.append(buildPriceDeltaPlaceholder());
        }

        row.append(separator())
                .append(Text.literal(NUMBER_FORMAT.format(Math.max(0, day.stock()))).formatted(Formatting.AQUA))
                .append(Text.literal("x").formatted(Formatting.GRAY));
        if (previous != null) {
            row.append(buildCountDeltaText(day.stock() - previous.stock()));
        } else {
            row.append(buildCountDeltaPlaceholder());
        }

        row.append(separator())
                .append(Text.literal(day.shops() + (day.shops() == 1 ? " shop" : " shops")).formatted(Formatting.GRAY));
        if (previous != null) {
            row.append(buildCountDeltaText(day.shops() - previous.shops()));
        } else {
            row.append(buildCountDeltaPlaceholder());
        }

        if (insights.highest() != null && insights.highest().equals(day)) {
            row.append(separator()).append(Text.literal("High").formatted(Formatting.GOLD));
        } else if (insights.lowest() != null && insights.lowest().equals(day)) {
            row.append(separator()).append(Text.literal("Low").formatted(Formatting.GREEN));
        }

        return row;
    }

    private static MutableText buildPriceDeltaText(double delta) {
        double magnitude = Math.abs(delta);
        if (magnitude < PRICE_DELTA_EPSILON) {
            return Text.literal(" ↔").formatted(Formatting.DARK_GRAY);
        }

        boolean isIncrease = delta > 0;
        Formatting color = isIncrease ? Formatting.GREEN : Formatting.RED;
        String arrow = isIncrease ? "▲" : "▼";
        return Text.literal(" " + arrow).formatted(color);
    }

    private static MutableText buildPriceDeltaPlaceholder() {
        return Text.literal("  ").formatted(Formatting.DARK_GRAY);
    }

    private static MutableText buildCountDeltaText(int delta) {
        if (delta == 0) {
            return Text.literal(" (→)").formatted(Formatting.DARK_GRAY);
        }

        boolean isIncrease = delta > 0;
        Formatting color = isIncrease ? Formatting.GREEN : Formatting.RED;
        String arrow = isIncrease ? "↑" : "↓";
        return Text.literal(" (" + arrow + ")").formatted(color);
    }

    private static MutableText buildCountDeltaPlaceholder() {
        return Text.literal(" ( )").formatted(Formatting.DARK_GRAY);
    }

    private static String formatHistoryDate(String raw) {
        LocalDate parsed = parseHistoryDate(raw);
        if (parsed != null) {
            return HISTORY_DISPLAY_FORMAT.format(parsed);
        }

        if (raw == null || raw.isBlank()) {
            return "??";
        }

        if (raw.length() >= 5) {
            return raw.substring(raw.length() - 5);
        }
        return raw;
    }

    private static LocalDate parseHistoryDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
        }

        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    private static int compareHistoryDates(PricebookQueryService.HistoryDay first,
                                           PricebookQueryService.HistoryDay second) {
        LocalDate firstDate = parseHistoryDate(first.date());
        LocalDate secondDate = parseHistoryDate(second.date());

        if (firstDate != null && secondDate != null) {
            return firstDate.compareTo(secondDate);
        }

        String firstRaw = first.date() == null ? "" : first.date();
        String secondRaw = second.date() == null ? "" : second.date();
        return firstRaw.compareTo(secondRaw);
    }

    private record HistoryInsights(
            List<PricebookQueryService.HistoryDay> days,
            PricebookQueryService.HistoryDay lowest,
            PricebookQueryService.HistoryDay highest
    ) {
    }

    private static void sendList(ClientPlayerEntity player, String title, List<Listing> entries, DecimalFormat priceFormatter) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        if (title != null && !title.isBlank()) {
            MutableText titleText = linePrefix()
                    .append(Text.literal(title).formatted(Formatting.GRAY, Formatting.UNDERLINE));
            player.sendMessage(titleText, false);
        }

        String playerDimension = Dimensions.canonical(player.getWorld());
        Instant now = Instant.now();

        int limit = Math.min(MAX_LISTINGS_DISPLAYED, entries.size());
        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            MutableText line = formatListing(i + 1, listing, playerDimension, now, priceFormatter);
            if (line != null) {
                player.sendMessage(line, false);
            }
        }
    }

    private static DecimalFormat createPriceFormatter(List<Listing> sellers, List<Listing> buyers) {
        boolean needsDecimals = anyPriceHasDecimals(sellers, buyers);
        return buildFormatter(needsDecimals);
    }

    private static DecimalFormat createPriceFormatterForHistory(List<PricebookQueryService.HistoryDay> historyDays) {
        boolean needsDecimals = false;
        if (historyDays != null) {
            for (PricebookQueryService.HistoryDay day : historyDays) {
                if (day.lowestPrice() % 1 != 0) {
                    needsDecimals = true;
                    break;
                }
            }
        }
        return buildFormatter(needsDecimals);
    }

    private static boolean anyPriceHasDecimals(List<Listing> sellers, List<Listing> buyers) {
        if (sellers != null) {
            for (Listing listing : sellers) {
                if (listing.price() % 1 != 0) {
                    return true;
                }
            }
        }
        if (buyers != null) {
            for (Listing listing : buyers) {
                if (listing.price() % 1 != 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static DecimalFormat buildFormatter(boolean needsDecimals) {
        String pattern = needsDecimals ? "#,##0.00" : "#,##0";
        DecimalFormat formatter = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setGroupingUsed(true);
        return formatter;
    }

    private static MutableText formatListing(int index, Listing listing, String playerDimension, Instant now, DecimalFormat priceFormatter) {
        if (listing == null) {
            return null;
        }

        String owner = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner();
        String priceStr = priceFormatter.format(listing.price());
        int amount = Math.max(0, listing.amount());

        MutableText line = linePrefix()
                .append(Text.literal("☆").formatted(Formatting.GRAY))
                .append(Text.literal(priceStr).formatted(Formatting.AQUA));

        MutableText quantityPart = Text.literal(NUMBER_FORMAT.format(amount)).formatted(Formatting.AQUA)
                .append(Text.literal("x").formatted(Formatting.GRAY));
        line.append(separator());
        line.append(quantityPart);

        line.append(separator());
        line.append(Text.literal(owner).formatted(Formatting.GRAY));

        String dimension = Dimensions.canonical(listing.dimension());
        String highlightDimension = dimension.isEmpty() ? playerDimension : dimension;

        appendWaystoneLink(line, listing, highlightDimension, playerDimension);
        appendCoordinateLink(line, listing, owner, highlightDimension);

        if (!dimension.isEmpty() && !dimension.equals(playerDimension)) {
            line.append(Text.literal(" (" + dimension + ")").formatted(Formatting.DARK_AQUA));
        }

        if (isStale(now, listing.lastSeenAt())) {
            line.append(Text.literal(" Stale").formatted(Formatting.YELLOW));
        }

        return line;
    }

    private static void appendWaystoneLink(MutableText line, Listing listing,
                                           String highlightDimension, String playerDimension) {
        WaystoneReference waystone = listing.nearestWaystone();
        if (waystone == null || waystone.position() == null) {
            return;
        }

        BlockPos wsPos = waystone.position();
        String wsName = waystone.name() == null || waystone.name().isBlank() ? "Waystone" : waystone.name();
        String dimensionArg = highlightDimension.isEmpty() ? playerDimension : highlightDimension;
        if (dimensionArg == null) {
            dimensionArg = "";
        }
        String wsCommand = waypointCommand(wsPos, dimensionArg, wsName);

        MutableText wsLink = Text.literal("[" + wsName + "]")
                .formatted(Formatting.GRAY)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(wsCommand))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Click to create waypoint at " + wsName))));

        line.append(separator());
        line.append(wsLink);
    }

    private static void appendCoordinateLink(MutableText line, Listing listing, String owner,
                                              String highlightDimension) {
        BlockPos listingPos = listing.position();
        if (listingPos == null) {
            return;
        }

        String waypointName = String.format("%s's Shop", owner);
        String command = waypointCommand(listingPos, highlightDimension, waypointName);
        MutableText coordsLink = Text.literal("[" + formatCoordinates(listingPos) + "]")
                .formatted(Formatting.GRAY)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Click to create waypoint at shop"))));
        line.append(separator());
        line.append(coordsLink);
    }

    private static MutableText separator() {
        return SEPARATOR.copy();
    }

    private static MutableText linePrefix() {
        return Text.literal("│ ").formatted(Formatting.AQUA);
    }

    private static ClientPlayerEntity validatePlayer(ClientPlayerEntity playerRef) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client != null ? client.player : null;
        if (player == null || playerRef == null || !Objects.equals(player.getUuid(), playerRef.getUuid())) {
            return null;
        }
        return player;
    }

    private static String waypointCommand(BlockPos pos, String dimension, String label) {
        String dimToken = (dimension == null || dimension.isBlank()) ? "_" : dimension;
        return String.format(Locale.ROOT, "/%s %d %d %d %s %s",
                WAYPOINT_COMMAND_NAME,
                pos.getX(), pos.getY(), pos.getZ(),
                dimToken,
                sanitizeNameForCommand(label));
    }

    private static String formatCoordinates(BlockPos listingPos) {
        if (listingPos == null) {
            return "coords n/a";
        }
        return String.format(Locale.ROOT, "%d %d %d", listingPos.getX(), listingPos.getY(), listingPos.getZ());
    }

    private static String sanitizeNameForCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Waystone";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static boolean isStale(Instant now, Instant lastSeen) {
        if (lastSeen == null || lastSeen.equals(Instant.EPOCH)) {
            return true;
        }
        Duration age = Duration.between(lastSeen, now).abs();
        return age.toMinutes() >= STALENESS_THRESHOLD_MINUTES;
    }

    private static String toTitleCase(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder converted = new StringBuilder();
        boolean convertNext = true;
        for (char ch : text.toCharArray()) {
            if (Character.isSpaceChar(ch)) {
                convertNext = true;
            } else if (convertNext) {
                ch = Character.toTitleCase(ch);
                convertNext = false;
            } else {
                ch = Character.toLowerCase(ch);
            }
            converted.append(ch);
        }
        return converted.toString();
    }

    private static MutableText prefixed(String message, Formatting formatting) {
        MutableText prefix = PREFIX.copy();
        if (message == null || message.isBlank()) {
            return prefix;
        }
        MutableText body = Text.literal(" " + message);
        if (formatting != null) {
            body = body.formatted(formatting);
        }
        return prefix.append(body);
    }
}
