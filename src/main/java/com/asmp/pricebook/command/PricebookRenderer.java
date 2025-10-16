package com.asmp.pricebook.command;

import com.asmp.pricebook.command.PricebookQueryService.ItemInfo;
import com.asmp.pricebook.command.PricebookQueryService.ItemLookupResult;
import com.asmp.pricebook.command.PricebookQueryService.Listing;
import com.asmp.pricebook.command.PricebookQueryService.WaystoneReference;
import com.asmp.pricebook.command.PricebookQueryService.PriceHistoryResult;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class PricebookRenderer {
    static final int MAX_LISTINGS_DISPLAYED = 3;
    static final int STALENESS_THRESHOLD_MINUTES = 60 * 24;
    static final String WAYPOINT_COMMAND_NAME = "pricebook_waypoint";
    private static final double PRICE_DELTA_EPSILON = 0.0001;

    static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final Text PREFIX = Text.literal("[Pricebook]").formatted(Formatting.AQUA);
    private static final Text SEPARATOR = Text.literal(" · ").formatted(Formatting.DARK_GRAY);
    private static final DateTimeFormatter HISTORY_DISPLAY_FORMAT = DateTimeFormatter.ofPattern("MM-dd", Locale.ROOT);
    static final String CONTENT_INDENT = "  ";
    static final int MAX_OWNER_DISPLAY_LENGTH = 18;
    private static final String[] SPACE_GLYPHS = {
            " ",
            "\u2000", "\u2001", "\u2002", "\u2003", "\u2004", "\u2005", "\u2006", "\u2007", "\u2008", "\u2009", "\u200A",
            "\u202F"
    };
    @FunctionalInterface
    interface WidthProvider {
        int width(Text text);
    }

    static {
        NUMBER_FORMAT.setGroupingUsed(true);
    }

    private PricebookRenderer() {
    }

    static WidthProvider createWidthProvider() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.textRenderer == null) {
            return text -> text.getString().length() * 6;
        }
        return client.textRenderer::getWidth;
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

        MutableText header = Text.literal("┌─ Pricebook ─ ").formatted(Formatting.AQUA)
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

        List<MutableText> tableLines = ListingTableFormatter.build(player, sellers, buyers, priceFormatter);
        for (MutableText line : tableLines) {
            player.sendMessage(line, false);
        }

        MutableText historyLink = Text.literal("└─ ").formatted(Formatting.AQUA)
                .append(Text.literal("[Price History]")
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/pricebook_history " + info.itemName()))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to view price history")))));
        player.sendMessage(historyLink, false);
    }

    public static void sendTestLayout(ClientPlayerEntity playerRef) {
        ClientPlayerEntity player = validatePlayer(playerRef);
        if (player == null) {
            return;
        }

        Instant now = Instant.now();

        List<Listing> sellers = List.of(
                new Listing("Wrathic", 2000.0, 1,
                        new BlockPos(-197, 69, 40),
                        Dimensions.OVERWORLD,
                        now.minus(Duration.ofMinutes(12)),
                        new WaystoneReference("Farmers Market", new BlockPos(-190, 68, 42), 72)),
                new Listing("Styxah", 3750.0, 43,
                        new BlockPos(30, 70, 30),
                        Dimensions.OVERWORLD,
                        now.minus(Duration.ofHours(2)),
                        new WaystoneReference("The Glorious Democratic People's Republic Of Rddubstan", new BlockPos(32, 70, 30), 18)),
                new Listing("burntbustybread", 3950.0, 14,
                        new BlockPos(-35, 88, -213),
                        Dimensions.NETHER,
                        now.minus(Duration.ofDays(3)),
                        new WaystoneReference("On God I'm going back", new BlockPos(-30, 88, -210), 40))
        );

        List<Listing> buyers = List.of(
                new Listing("ProbablyNotJacob", 3800.0, 1728,
                        new BlockPos(128, 72, -512),
                        Dimensions.OVERWORLD,
                        now.minus(Duration.ofMinutes(5)),
                        new WaystoneReference("Amethyst Village", new BlockPos(132, 72, -508), 28)),
                new Listing("Styxah", 3500.0, 11,
                        new BlockPos(-210, 68, 44),
                        Dimensions.OVERWORLD,
                        now.minus(Duration.ofHours(4)),
                        new WaystoneReference("Farmers Market", new BlockPos(-190, 68, 42), 72)),
                new Listing("burntbustybread", 3250.0, 61,
                        new BlockPos(96, 64, -1024),
                        Dimensions.END,
                        now.minus(Duration.ofHours(30)),
                        new WaystoneReference("On God I'm going back", new BlockPos(100, 64, -1020), 36))
        );

        MutableText header = Text.literal("┌─ Pricebook Test ─ Sample Layout").formatted(Formatting.AQUA);
        player.sendMessage(header, false);

        DecimalFormat priceFormatter = createPriceFormatter(sellers, buyers);
        for (MutableText line : ListingTableFormatter.build(player, sellers, buyers, priceFormatter)) {
            player.sendMessage(line, false);
        }

        MutableText footer = Text.literal("└─ End Test Layout").formatted(Formatting.AQUA);
        player.sendMessage(footer, false);
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

        MutableText header = Text.literal("┌─ Pricebook History ─ ").formatted(Formatting.AQUA)
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
        WidthProvider widthProvider = createWidthProvider();
        int labelWidth = 0;
        int priceWidth = 0;
        int stockWidth = 0;
        int shopsWidth = 0;
        for (int i = 0; i < size; i++) {
            PricebookQueryService.HistoryDay day = orderedDays.get(i);
            String label = (i == 0) ? "Latest" : formatHistoryDate(day.date());
            labelWidth = Math.max(labelWidth, measureWidth(widthProvider, Text.literal(label).formatted(Formatting.GRAY)));
            String priceStr = priceFormatter.format(day.lowestPrice());
            priceWidth = Math.max(priceWidth, measureWidth(widthProvider, Text.literal(priceStr).formatted(Formatting.AQUA)));
            String stockStr = NUMBER_FORMAT.format(Math.max(0, day.stock()));
            stockWidth = Math.max(stockWidth, measureWidth(widthProvider, Text.literal(stockStr).formatted(Formatting.AQUA)));
            String shopsStr = day.shops() + (day.shops() == 1 ? " shop" : " shops");
            shopsWidth = Math.max(shopsWidth, measureWidth(widthProvider, Text.literal(shopsStr).formatted(Formatting.GRAY)));
        }

        for (int i = 0; i < size; i++) {
            PricebookQueryService.HistoryDay day = orderedDays.get(i);
            PricebookQueryService.HistoryDay previous = (i + 1) < size ? orderedDays.get(i + 1) : null;
            boolean isLatest = i == 0;
            MutableText row = buildHistoryRow(day, previous, priceFormatter, insights, isLatest,
                    labelWidth, priceWidth, stockWidth, shopsWidth, widthProvider);
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
                                               boolean isLatest,
                                               int labelWidth,
                                               int priceWidth,
                                               int stockWidth,
                                               int shopsWidth,
                                               WidthProvider widthProvider) {
        String label = isLatest ? "Latest" : formatHistoryDate(day.date());
        MutableText row = linePrefix().append(Text.literal(CONTENT_INDENT));
        row.append(padRight(widthProvider, Text.literal(label).formatted(Formatting.GRAY), labelWidth));

        Formatting priceColor = Formatting.AQUA;
        if (insights.highest() != null && insights.highest().equals(day)) {
            priceColor = Formatting.GOLD;
        } else if (insights.lowest() != null && insights.lowest().equals(day)) {
            priceColor = Formatting.GREEN;
        }

        row.append(separator());
        row.append(padLeft(widthProvider, Text.literal(priceFormatter.format(day.lowestPrice())).formatted(priceColor), priceWidth));

        if (previous != null) {
            row.append(buildPriceDeltaText(day.lowestPrice() - previous.lowestPrice()));
        } else {
            row.append(buildPriceDeltaPlaceholder());
        }

        String stockStr = NUMBER_FORMAT.format(Math.max(0, day.stock()));
        row.append(separator());
        MutableText stockComponent = padLeft(widthProvider, Text.literal(stockStr).formatted(Formatting.AQUA), stockWidth)
                .append(Text.literal("x").formatted(Formatting.GRAY));
        row.append(stockComponent);
        if (previous != null) {
            row.append(buildCountDeltaText(day.stock() - previous.stock()));
        } else {
            row.append(buildCountDeltaPlaceholder());
        }

        String shopsStr = day.shops() + (day.shops() == 1 ? " shop" : " shops");
        row.append(separator());
        row.append(padRight(widthProvider, Text.literal(shopsStr).formatted(Formatting.GRAY), shopsWidth));
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
            return Text.literal(" →").formatted(Formatting.DARK_GRAY);
        }

        boolean isIncrease = delta > 0;
        Formatting color = isIncrease ? Formatting.GREEN : Formatting.RED;
        String arrow = isIncrease ? "↑" : "↓";
        return Text.literal(" " + arrow).formatted(color);
    }

    private static MutableText buildPriceDeltaPlaceholder() {
        return Text.literal("  ").formatted(Formatting.DARK_GRAY);
    }

    private static MutableText buildCountDeltaText(int delta) {
        if (delta == 0) {
            return Text.literal(" →").formatted(Formatting.DARK_GRAY);
        }

        boolean isIncrease = delta > 0;
        Formatting color = isIncrease ? Formatting.GREEN : Formatting.RED;
        String arrow = isIncrease ? "↑" : "↓";
        return Text.literal(" " + arrow).formatted(color);
    }

    private static MutableText buildCountDeltaPlaceholder() {
        return Text.literal("  ").formatted(Formatting.DARK_GRAY);
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

    static DecimalFormat createPriceFormatter(List<Listing> sellers, List<Listing> buyers) {
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

    static MutableText separator() {
        return SEPARATOR.copy();
    }

    static MutableText linePrefix() {
        return Text.literal("│ ").formatted(Formatting.AQUA);
    }

    static MutableText padLeft(WidthProvider widthProvider, Text text, int targetWidth) {
        MutableText padded = Text.empty();
        if (widthProvider == null) {
            padded.append(Text.literal(" ".repeat(Math.max(0, (targetWidth / 4)))));
            padded.append(text.copy());
            return padded;
        }

        int width = measureWidth(widthProvider, text);
        if (width >= targetWidth) {
            padded.append(text.copy());
            return padded;
        }

        padded.append(spacer(widthProvider, targetWidth - width));
        padded.append(text.copy());
        return padded;
    }

    static MutableText padRight(WidthProvider widthProvider, Text text, int targetWidth) {
        MutableText padded = Text.empty();
        padded.append(text.copy());
        if (widthProvider == null) {
            padded.append(Text.literal(" ".repeat(Math.max(0, (targetWidth / 4)))));
            return padded;
        }

        int width = measureWidth(widthProvider, text);
        if (width >= targetWidth) {
            return padded;
        }

        padded.append(spacer(widthProvider, targetWidth - width));
        return padded;
    }

    static MutableText spacer(WidthProvider widthProvider, int pixels) {
        MutableText filler = Text.empty();
        if (pixels <= 0) {
            return filler;
        }

        if (widthProvider == null) {
            int spaces = Math.max(1, (int) Math.ceil(pixels / 4.0));
            filler.append(Text.literal(" ".repeat(spaces)));
            return filler;
        }

        List<SpaceUnit> units = spaceUnits(widthProvider);
        if (units.isEmpty()) {
            int spaces = Math.max(1, (int) Math.ceil(pixels / 4.0));
            filler.append(Text.literal(" ".repeat(spaces)));
            return filler;
        }

        int generated = 0;
        while (generated < pixels) {
            int remaining = pixels - generated;
            SpaceUnit unit = pickSpaceUnit(units, remaining);
            filler.append(unit.text.copy());
            generated += unit.width;
        }

        return filler;
    }

    static int measureWidth(WidthProvider widthProvider, Text text) {
        if (widthProvider != null) {
            return widthProvider.width(text);
        }
        return text.getString().length() * 6;
    }

    private static List<SpaceUnit> spaceUnits(WidthProvider widthProvider) {
        if (widthProvider == null) {
            return List.of();
        }
        List<SpaceUnit> units = new ArrayList<>();
        for (String glyph : SPACE_GLYPHS) {
            Text candidate = Text.literal(glyph);
            int width = widthProvider.width(candidate);
            if (width > 0) {
                units.add(new SpaceUnit(candidate, width));
            }
        }
        units.sort(Comparator.comparingInt(SpaceUnit::width).reversed());
        return units;
    }

    private static SpaceUnit pickSpaceUnit(List<SpaceUnit> units, int remaining) {
        for (SpaceUnit unit : units) {
            if (unit.width <= remaining) {
                return unit;
            }
        }
        return units.get(units.size() - 1);
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= 3) {
            return value.substring(0, maxLength);
        }
        return value.substring(0, maxLength - 3) + "...";
    }

    private record SpaceUnit(Text text, int width) {
    }

    private static ClientPlayerEntity validatePlayer(ClientPlayerEntity playerRef) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client != null ? client.player : null;
        if (player == null || playerRef == null || !Objects.equals(player.getUuid(), playerRef.getUuid())) {
            return null;
        }
        return player;
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
