package com.asmp.pricebook.command;

import com.asmp.pricebook.command.PricebookQueryService.Listing;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.asmp.pricebook.command.PricebookRenderer.CONTENT_INDENT;
import static com.asmp.pricebook.command.PricebookRenderer.MAX_LISTINGS_DISPLAYED;
import static com.asmp.pricebook.command.PricebookRenderer.MAX_OWNER_DISPLAY_LENGTH;
import static com.asmp.pricebook.command.PricebookRenderer.NUMBER_FORMAT;
import static com.asmp.pricebook.command.PricebookRenderer.STALENESS_THRESHOLD_MINUTES;
import static com.asmp.pricebook.command.PricebookRenderer.WAYPOINT_COMMAND_NAME;
import static com.asmp.pricebook.command.PricebookRenderer.WidthProvider;
import static com.asmp.pricebook.command.PricebookRenderer.createWidthProvider;
import static com.asmp.pricebook.command.PricebookRenderer.linePrefix;
import static com.asmp.pricebook.command.PricebookRenderer.measureWidth;
import static com.asmp.pricebook.command.PricebookRenderer.padLeft;
import static com.asmp.pricebook.command.PricebookRenderer.padRight;
import static com.asmp.pricebook.command.PricebookRenderer.separator;
import static com.asmp.pricebook.command.PricebookRenderer.truncate;

final class ListingTableFormatter {
    private final DecimalFormat priceFormatter;
    private final WidthProvider widthProvider;
    private final String playerDimension;
    private final Instant now;
    private final int maxLineWidth;
    private final List<MutableText> lines = new ArrayList<>();
    private int maxPriceWidth;
    private int maxAmountWidth;
    private int maxOwnerWidth;
    private int maxWaystoneWidth;
    private int ownerColumnWidth;
    private int waystoneColumnWidth;
    private boolean hasRenderedSection;

    private ListingTableFormatter(ClientPlayerEntity player, DecimalFormat priceFormatter) {
        this(priceFormatter,
                createWidthProvider(),
                Dimensions.canonical(player.getWorld()),
                Instant.now(),
                resolveChatWidth());
    }

    private ListingTableFormatter(DecimalFormat priceFormatter,
                                  WidthProvider widthProvider,
                                  String playerDimension,
                                  Instant now,
                                  int maxLineWidth) {
        this.priceFormatter = priceFormatter;
        this.widthProvider = widthProvider;
        this.playerDimension = playerDimension == null ? "" : playerDimension;
        this.now = now == null ? Instant.now() : now;
        this.maxLineWidth = maxLineWidth <= 0 ? Integer.MAX_VALUE : maxLineWidth;
    }

    static List<MutableText> build(ClientPlayerEntity player,
                                   List<Listing> sellers,
                                   List<Listing> buyers,
                                   DecimalFormat priceFormatter) {
        ListingTableFormatter formatter = new ListingTableFormatter(player, priceFormatter);
        formatter.prepareColumns(sellers, buyers);
        formatter.appendSection(null, sellers);
        formatter.appendSection("Buyers", buyers);
        return formatter.lines;
    }

    static List<MutableText> buildForTest(List<Listing> sellers,
                                          List<Listing> buyers,
                                          DecimalFormat priceFormatter,
                                          WidthProvider widthProvider,
                                          String playerDimension,
                                          Instant now,
                                          int maxLineWidth) {
        ListingTableFormatter formatter = new ListingTableFormatter(priceFormatter, widthProvider, playerDimension, now, maxLineWidth);
        formatter.prepareColumns(sellers, buyers);
        formatter.appendSection(null, sellers);
        formatter.appendSection("Buyers", buyers);
        return formatter.lines;
    }

    private void prepareColumns(List<Listing> sellers, List<Listing> buyers) {
        collectWidths(sellers);
        collectWidths(buyers);
        adjustColumnWidths(sellers, buyers);
    }

    private void collectWidths(List<Listing> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        int limit = Math.min(MAX_LISTINGS_DISPLAYED, entries.size());
        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            if (listing == null) {
                continue;
            }

            String priceStr = priceFormatter.format(listing.price());
            Text priceText = Text.literal(priceStr).formatted(Formatting.AQUA);
            maxPriceWidth = Math.max(maxPriceWidth, measureWidth(widthProvider, priceText));

            int amount = Math.max(0, listing.amount());
            String amountStr = NUMBER_FORMAT.format(amount);
            Text amountText = Text.literal(amountStr).formatted(Formatting.AQUA);
            maxAmountWidth = Math.max(maxAmountWidth, measureWidth(widthProvider, amountText));

            String owner = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner();
            String ownerDisplay = truncate(owner, MAX_OWNER_DISPLAY_LENGTH);
            Text ownerText = Text.literal(ownerDisplay).formatted(Formatting.GRAY);
            maxOwnerWidth = Math.max(maxOwnerWidth, measureWidth(widthProvider, ownerText));

            String waystone = extractWaystoneName(listing);
            if (waystone != null && !waystone.isBlank()) {
                maxWaystoneWidth = Math.max(maxWaystoneWidth, measureWaystoneWidth(waystone));
            }
        }
    }

    private void adjustColumnWidths(List<Listing> sellers, List<Listing> buyers) {
        ownerColumnWidth = maxOwnerWidth;
        waystoneColumnWidth = Math.max(maxWaystoneWidth, waystoneMinimumWidth());

        if (widthProvider == null || maxLineWidth == Integer.MAX_VALUE) {
            maxOwnerWidth = ownerColumnWidth;
            maxWaystoneWidth = waystoneColumnWidth;
            return;
        }

        List<Listing> combined = combinedListings(sellers, buyers);

        while (computeMaxRowWidth(combined, ownerColumnWidth, waystoneColumnWidth) > maxLineWidth) {
            boolean reduced = false;

            if (ownerColumnWidth > waystoneColumnWidth && ownerColumnWidth > ownerMinimumWidth()) {
                ownerColumnWidth--;
                reduced = true;
            } else if (waystoneColumnWidth > ownerColumnWidth && waystoneColumnWidth > waystoneMinimumWidth()) {
                waystoneColumnWidth--;
                reduced = true;
            } else {
                boolean ownerReduced = false;
                boolean wayReduced = false;
                if (ownerColumnWidth > ownerMinimumWidth()) {
                    ownerColumnWidth--;
                    ownerReduced = true;
                }
                if (waystoneColumnWidth > waystoneMinimumWidth()) {
                    waystoneColumnWidth--;
                    wayReduced = true;
                }
                reduced = ownerReduced || wayReduced;
                if (!reduced) {
                    break;
                }
            }

            if (!reduced) {
                break;
            }
        }

        maxOwnerWidth = ownerColumnWidth;
        maxWaystoneWidth = waystoneColumnWidth;
    }

    private int computeMaxRowWidth(List<Listing> combined, int ownerWidth, int wayWidth) {
        int previousOwnerWidth = maxOwnerWidth;
        int previousWayWidth = maxWaystoneWidth;
        maxOwnerWidth = ownerWidth;
        maxWaystoneWidth = wayWidth;
        int max = 0;
        for (Listing listing : combined) {
            if (listing == null) {
                continue;
            }
            String ownerOriginal = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner().trim();
            ownerOriginal = truncate(ownerOriginal, MAX_OWNER_DISPLAY_LENGTH);
            String ownerDisplay = trimOwnerToWidth(ownerOriginal, ownerWidth);

            String wayOriginal = extractWaystoneName(listing);
            String wayDisplay = trimWaystoneToWidth(wayOriginal, wayWidth);

            MutableText candidate = composeListingLine(listing, ownerOriginal, ownerDisplay, wayDisplay, wayOriginal);
            int width = measureWidth(widthProvider, candidate.copy());
            if (width > max) {
                max = width;
            }
        }
        maxOwnerWidth = previousOwnerWidth;
        maxWaystoneWidth = previousWayWidth;
        return max;
    }

    private void appendSection(String title, List<Listing> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        int limit = Math.min(MAX_LISTINGS_DISPLAYED, entries.size());
        if (limit == 0) {
            return;
        }

        if (title != null && !title.isBlank()) {
            MutableText heading = linePrefix()
                    .append(Text.literal(CONTENT_INDENT))
                    .append(Text.literal(title).formatted(Formatting.GRAY));
            lines.add(heading);
        }

        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            MutableText row = buildListingLine(listing, true);
            if (row != null) {
                lines.add(row);
            }
        }

        hasRenderedSection = true;
    }

    private MutableText buildListingLine(Listing listing) {
        return buildListingLine(listing, true);
    }

    private MutableText buildListingLine(Listing listing, boolean allowWaystoneCompaction) {
        if (listing == null) {
            return null;
        }

        String ownerOriginal = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner().trim();
        ownerOriginal = truncate(ownerOriginal, MAX_OWNER_DISPLAY_LENGTH);
        String ownerDisplay = trimOwnerToWidth(ownerOriginal, ownerColumnWidth);

        String waystoneOriginal = extractWaystoneName(listing);
        String waystoneDisplay = trimWaystoneToWidth(waystoneOriginal, waystoneColumnWidth);

        return composeListingLine(listing, ownerOriginal, ownerDisplay, waystoneDisplay, waystoneOriginal);
    }

    private MutableText composeListingLine(Listing listing,
                                           String ownerOriginal,
                                           String ownerDisplay,
                                           String waystoneDisplay,
                                           String waystoneOriginal) {
        String priceStr = priceFormatter.format(listing.price());
        int amount = Math.max(0, listing.amount());

        MutableText line = linePrefix()
                .append(Text.literal(CONTENT_INDENT))
                .append(padLeft(widthProvider, Text.literal(priceStr).formatted(Formatting.AQUA), maxPriceWidth));

        String amountStr = NUMBER_FORMAT.format(amount);
        MutableText quantityPart = padLeft(widthProvider, Text.literal(amountStr).formatted(Formatting.AQUA), maxAmountWidth)
                .append(Text.literal("x").formatted(Formatting.GRAY));
        line.append(separator());
        line.append(quantityPart);

        line.append(separator());
        MutableText ownerText = Text.literal(ownerDisplay);
        if (!ownerDisplay.equals(ownerOriginal.trim())) {
            ownerText = ownerText.styled(style -> style.withHoverEvent(new HoverEvent.ShowText(Text.literal(ownerOriginal))));
        }
        ownerText = ownerText.formatted(Formatting.GRAY);
        line.append(padRight(widthProvider, ownerText, maxOwnerWidth));

        String dimension = Dimensions.canonical(listing.dimension());
        String highlightDimension = dimension.isEmpty() ? playerDimension : dimension;

        appendWaystoneLink(line, listing, highlightDimension, playerDimension, waystoneDisplay, waystoneOriginal);
        appendCoordinateLink(line, listing, ownerOriginal, highlightDimension);

        if (!dimension.isEmpty() && !dimension.equals(playerDimension)) {
            line.append(Text.literal(" (" + dimension + ")").formatted(Formatting.DARK_AQUA));
        }

        if (isStale(now, listing.lastSeenAt())) {
            line.append(Text.literal(" Stale").formatted(Formatting.YELLOW));
        }

        return line;
    }

    private void appendWaystoneLink(MutableText line,
                                    Listing listing,
                                    String highlightDimension,
                                    String playerDimension,
                                    String displayName,
                                    String originalName) {
        WaystoneReference waystone = listing.nearestWaystone();
        if (waystone == null || waystone.position() == null) {
            return;
        }

        BlockPos wsPos = waystone.position();
        String wsName = originalName;
        if (wsName == null || wsName.isBlank()) {
            wsName = waystone.name() == null || waystone.name().isBlank() ? "Waystone" : waystone.name().trim();
        }
        String display = displayName;
        if (display == null || display.isBlank()) {
            display = wsName;
        }
        String dimensionArg = highlightDimension.isEmpty() ? playerDimension : highlightDimension;
        if (dimensionArg == null) {
            dimensionArg = "";
        }
        String wsCommand = waypointCommand(wsPos, dimensionArg, wsName);
        final String tooltipName = wsName;

        MutableText wsLink = Text.literal("[" + display + "]")
                .formatted(Formatting.GRAY)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(wsCommand))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Click to create waypoint at " + tooltipName))));

        line.append(separator());
        line.append(wsLink);
    }

    private void appendCoordinateLink(MutableText line,
                                      Listing listing,
                                      String owner,
                                      String highlightDimension) {
        BlockPos listingPos = listing.position();
        if (listingPos == null) {
            return;
        }

        String waypointName = String.format("%s's Shop", owner);
        String command = waypointCommand(listingPos, highlightDimension, waypointName);
        MutableText coordsLink = Text.literal("[◆]")
                .formatted(Formatting.GRAY)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand(command))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Text.literal("Click to create waypoint at shop\n" + formatCoordinates(listingPos)))));
        line.append(separator());
        line.append(coordsLink);
    }

    private static boolean isStale(Instant now, Instant lastSeen) {
        if (lastSeen == null || lastSeen.equals(Instant.EPOCH)) {
            return true;
        }
        Duration age = Duration.between(lastSeen, now).abs();
        return age.toMinutes() >= STALENESS_THRESHOLD_MINUTES;
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

    private static int resolveChatWidth() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null || client.inGameHud.getChatHud() == null) {
            return Integer.MAX_VALUE;
        }
        return client.inGameHud.getChatHud().getWidth();
    }

    private List<Listing> combinedListings(List<Listing> sellers, List<Listing> buyers) {
        List<Listing> combined = new ArrayList<>();
        appendLimited(combined, sellers);
        appendLimited(combined, buyers);
        return combined;
    }

    private void appendLimited(List<Listing> target, List<Listing> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }
        int limit = Math.min(MAX_LISTINGS_DISPLAYED, entries.size());
        for (int i = 0; i < limit; i++) {
            target.add(entries.get(i));
        }
    }

    private String extractWaystoneName(Listing listing) {
        WaystoneReference waystone = listing.nearestWaystone();
        if (waystone == null) {
            return null;
        }
        String raw = waystone.name();
        if (raw == null || raw.isBlank()) {
            return "Waystone";
        }
        return raw.trim();
    }

    private String trimOwnerToWidth(String original, int targetWidth) {
        int bounded = Math.min(targetWidth, maxOwnerWidth);
        return trimToWidth(original, bounded, false);
    }

    private String trimWaystoneToWidth(String original, int totalTargetWidth) {
        if (original == null) {
            return "";
        }
        return trimToWidth(original, totalTargetWidth, true);
    }

    private String trimToWidth(String value, int targetWidth, boolean bracketed) {
        if (value == null) {
            value = "";
        }
        String trimmed = value.trim();
        if (targetWidth <= 0) {
            return "";
        }

        int currentWidth = bracketed ? measureWaystoneWidth(trimmed) : measureOwnerWidth(trimmed);
        if (currentWidth <= targetWidth) {
            return trimmed;
        }

        String ellipsis = "…";
        int ellipsisWidth = bracketed ? measureWaystoneWidth(ellipsis) : measureOwnerWidth(ellipsis);
        if (ellipsisWidth > targetWidth) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            builder.append(trimmed.charAt(i));
            String candidate = builder + ellipsis;
            int candidateWidth = bracketed ? measureWaystoneWidth(candidate) : measureOwnerWidth(candidate);
            if (candidateWidth > targetWidth) {
                builder.setLength(Math.max(0, builder.length() - 1));
                break;
            }
        }

        while (builder.length() > 0) {
            String candidate = builder + ellipsis;
            int candidateWidth = bracketed ? measureWaystoneWidth(candidate) : measureOwnerWidth(candidate);
            if (candidateWidth <= targetWidth) {
                return candidate;
            }
            builder.setLength(builder.length() - 1);
        }

        return ellipsis;
    }

    private String padWaystoneDisplay(String base) {
        String inner = base == null ? "" : base;
        if (widthProvider == null || waystoneColumnWidth <= 0) {
            return "[" + inner + "]";
        }
        int guard = 0;
        while (measureWaystoneWidth(inner) < waystoneColumnWidth && guard++ < 128) {
            inner += " ";
        }
        return "[" + inner + "]";
    }

    private int measureOwnerWidth(String value) {
        return measureWidth(widthProvider, Text.literal(value == null ? "" : value));
    }

    private int measureWaystoneWidth(String value) {
        String inside = value == null ? "" : value;
        return measureWidth(widthProvider, Text.literal("[" + inside + "]"));
    }

    private int ownerMinimumWidth() {
        return measureOwnerWidth("…");
    }

    private int waystoneMinimumWidth() {
        return measureWaystoneWidth("…");
    }
}
