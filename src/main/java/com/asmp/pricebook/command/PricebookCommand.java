package com.asmp.pricebook.command;

import com.asmp.pricebook.Pricebook;
import com.asmp.pricebook.command.PricebookQueryService.ItemInfo;
import com.asmp.pricebook.command.PricebookQueryService.ItemLookupResult;
import com.asmp.pricebook.command.PricebookQueryService.Listing;
import com.asmp.pricebook.command.PricebookQueryService.WaystoneReference;
import com.asmp.pricebook.util.Dimensions;
import com.asmp.pricebook.waypoint.WaypointManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.item.ItemStack;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PricebookCommand {
    private static final int STALENESS_THRESHOLD_MINUTES = 60 * 24;
    private static final int MAX_LISTINGS_DISPLAYED = 3;

    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("0.00",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final DecimalFormat NUMBER_FORMAT = new DecimalFormat("#,##0",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final String WAYPOINT_COMMAND_NAME = "pricebook_waypoint";

    static {
        PRICE_FORMAT.setGroupingUsed(true);
    }

    private PricebookCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerPricebookCommand(dispatcher, "pricebook");
            registerPricebookCommand(dispatcher, "pb");
            registerHistoryCommand(dispatcher, "pricebook_history");
            registerWaypointCommand(dispatcher);
        });
    }

    private static void registerPricebookCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, String alias) {
        dispatcher.register(ClientCommandManager.literal(alias)
                .executes(ctx -> execute(ctx.getSource(), null))
                .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                        .suggests(PricebookCommand::suggestItems)
                        .executes(ctx -> execute(ctx.getSource(), StringArgumentType.getString(ctx, "item")))));
    }

    private static void registerHistoryCommand(CommandDispatcher<FabricClientCommandSource> dispatcher, String alias) {
        dispatcher.register(ClientCommandManager.literal(alias)
                .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                        .suggests(PricebookCommand::suggestItems)
                        .executes(ctx -> executeHistory(ctx.getSource(), StringArgumentType.getString(ctx, "item")))));
    }

    private static void registerWaypointCommand(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(ClientCommandManager.literal(WAYPOINT_COMMAND_NAME)
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                        .then(ClientCommandManager.argument("dimension", StringArgumentType.word())
                                                .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> createWaypoint(ctx.getSource(),
                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                StringArgumentType.getString(ctx, "dimension"),
                                                                StringArgumentType.getString(ctx, "name")))))))));
    }

    private static int execute(FabricClientCommandSource source, String itemName) {
        MinecraftClient client = source.getClient();
        if (client == null) {
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        WaypointManager.clear(); // Always clear the waypoint when running command

        if (!Pricebook.isEnabled()) {
            player.sendMessage(prefixed("Disabled.", Formatting.RED), false);
            return 1;
        }

        PricebookQueryService service = Pricebook.queryService();
        if (service == null) {
            player.sendMessage(prefixed("Query service not available.", Formatting.RED), false);
            return 1;
        }

        String resolved = resolveItemName(player, itemName);
        if (resolved == null || resolved.isBlank()) {
            player.sendMessage(prefixed("Hold an item or specify a name.", Formatting.RED), false);
            return 1;
        }

        CompletableFuture<ItemLookupResult> future = service.lookup(resolved);
        future.thenAccept(result -> client.execute(() -> deliverResult(player, result)));

        return 1;
    }

    private static int executeHistory(FabricClientCommandSource source, String itemName) {
        MinecraftClient client = source.getClient();
        if (client == null) {
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        if (!Pricebook.isEnabled()) {
            player.sendMessage(prefixed("Disabled.", Formatting.RED), false);
            return 1;
        }

        PricebookQueryService service = Pricebook.queryService();
        if (service == null) {
            player.sendMessage(prefixed("Query service not available.", Formatting.RED), false);
            return 1;
        }

        String trimmed = itemName == null ? "" : itemName.trim();
        if (trimmed.isEmpty()) {
            player.sendMessage(prefixed("Specify an item name.", Formatting.RED), false);
            return 1;
        }

        CompletableFuture<PricebookQueryService.PriceHistoryResult> future = service.fetchHistory(trimmed);
        future.thenAccept(result -> client.execute(() -> deliverHistoryResult(player, result)));

        return 1;
    }

    private static String resolveItemName(ClientPlayerEntity player, String itemArgument) {
        if (itemArgument != null && !itemArgument.isBlank()) {
            return itemArgument.trim();
        }

        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) {
            stack = player.getOffHandStack();
        }
        if (stack.isEmpty()) {
            return null;
        }

        String display = stack.getName().getString();
        return display == null ? null : display.trim();
    }

    private static CompletableFuture<Suggestions> suggestItems(CommandContext<FabricClientCommandSource> context,
                                                               SuggestionsBuilder builder) {
        List<String> catalog = Pricebook.itemCatalog();
        if (catalog == null || catalog.isEmpty()) {
            return builder.buildFuture();
        }

        String input = builder.getRemaining().toLowerCase(Locale.ROOT);
        if (input.isBlank()) {
            catalog.stream()
                .map(item -> item.toLowerCase(Locale.ROOT))
                .forEach(builder::suggest);
            return builder.buildFuture();
        }

        String[] tokens = input.trim().split("\\s+");

        catalog.stream()
            .filter(item -> {
                String itemLower = item.toLowerCase(Locale.ROOT);
                for (String token : tokens) {
                    if (!itemLower.contains(token)) {
                        return false;
                    }
                }
                return true;
            })
            .map(item -> item.toLowerCase(Locale.ROOT))
            .sorted((aLower, bLower) -> {
                // Prioritize exact matches first
                boolean aExact = aLower.equals(input);
                boolean bExact = bLower.equals(input);
                if (aExact != bExact) {
                    return aExact ? -1 : 1;
                }

                // Then prioritize items that start with the input
                boolean aStarts = aLower.startsWith(input);
                boolean bStarts = bLower.startsWith(input);
                if (aStarts != bStarts) {
                    return aStarts ? -1 : 1;
                }

                // Then by length (shorter = more relevant)
                return Integer.compare(aLower.length(), bLower.length());
            })
            .forEach(builder::suggest);

        return builder.buildFuture();
    }

    private static int createWaypoint(FabricClientCommandSource source, int x, int y, int z,
                                      String dimensionArg, String name) {
        MinecraftClient client = source.getClient();
        if (client == null) {
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        boolean usePlayerDimension = dimensionArg == null || dimensionArg.isBlank() || "_".equals(dimensionArg);
        String dimension = usePlayerDimension
                ? Dimensions.canonical(player.getWorld())
                : Dimensions.canonical(dimensionArg);

        BlockPos pos = new BlockPos(x, y, z);
        boolean success = WaypointManager.createWaypoint(pos, dimension, name);
        if (success) {
            String displayName = name == null || name.isBlank() ? "Waystone" : name;
            String message = String.format(Locale.ROOT, "Created waypoint '%s'. Run /pb again to clear", displayName);
            player.sendMessage(prefixed(message, Formatting.AQUA), false);
            return 1;
        }

        player.sendMessage(prefixed("Unable to create waypoint.", Formatting.RED), false);
        return 0;
    }

    private static void deliverResult(ClientPlayerEntity playerRef, ItemLookupResult result) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client != null ? client.player : null;
        if (player == null || playerRef == null || !Objects.equals(player.getUuid(), playerRef.getUuid())) {
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
            player.sendMessage(Text.literal("│ ").formatted(Formatting.AQUA).append(Text.literal("No buyers or sellers yet.").formatted(Formatting.GRAY)), false);
            return;
        }

        DecimalFormat priceFormatter = createPriceFormatter(sellers, buyers);

        sendList(player, "Sellers", sellers, priceFormatter);

        if (!noBuyers) {
            sendList(player, "Buyers", buyers, priceFormatter);
        }

        MutableText historyLink = Text.literal("│ ").formatted(Formatting.AQUA)
                .append(Text.literal("[View Price History]")
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.RunCommand("/pricebook_history " + info.itemName()))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to view price history")))));
        player.sendMessage(historyLink, false);
    }

    private static void deliverHistoryResult(ClientPlayerEntity playerRef, PricebookQueryService.PriceHistoryResult result) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client != null ? client.player : null;
        if (player == null || playerRef == null || !Objects.equals(player.getUuid(), playerRef.getUuid())) {
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

        DecimalFormat priceFormatter = createPriceFormatterForHistory(historyDays);

        for (PricebookQueryService.HistoryDay day : historyDays) {
            double price = day.lowestPrice();

            // Skip rows where price is null/invalid
            if (price <= 0) {
                sendSpacerLine(player);
                continue;
            }

            String dateStr = day.date();
            // Remove year from date (2025-10-04 -> 10-04)
            if (dateStr != null && dateStr.length() >= 10) {
                dateStr = dateStr.substring(5); // Skip "YYYY-" part
            }
            int stock = day.stock();
            int shops = day.shops();

            MutableText row = Text.literal("│ ").formatted(Formatting.AQUA)
                    .append(Text.literal(dateStr).formatted(Formatting.GRAY))
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal("☆").formatted(Formatting.GRAY))
                    .append(Text.literal(priceFormatter.format(price)).formatted(Formatting.AQUA))
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(NUMBER_FORMAT.format(stock)).formatted(Formatting.AQUA))
                    .append(Text.literal("x").formatted(Formatting.GRAY))
                    .append(Text.literal(" · ").formatted(Formatting.DARK_GRAY))
                    .append(Text.literal(shops + " shops").formatted(Formatting.GRAY));
            player.sendMessage(row, false);
        }
    }

    private static void sendSpacerLine(ClientPlayerEntity player) {
        if (player == null) {
            return;
        }
        player.sendMessage(Text.literal("│ ").formatted(Formatting.AQUA), false);
    }

    private static void sendList(ClientPlayerEntity player, String title, List<Listing> entries, DecimalFormat priceFormatter) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        MutableText titleText = Text.literal("│ ").formatted(Formatting.AQUA)
                .append(Text.literal(title).formatted(Formatting.GRAY, Formatting.UNDERLINE));
        player.sendMessage(titleText, false);

        String playerDimension = Dimensions.canonical(player.getWorld());
        Instant now = Instant.now();

        int limit = Math.min(MAX_LISTINGS_DISPLAYED, entries.size());
        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            MutableText line = ListingFormatter.format(i + 1, listing, playerDimension, now, priceFormatter);
            if (line != null) {
                player.sendMessage(line, false);
            }
        }
    }

    private static boolean isStale(Instant now, Instant lastSeen) {
        if (lastSeen == null || lastSeen.equals(Instant.EPOCH)) {
            return true;
        }
        Duration age = Duration.between(lastSeen, now).abs();
        return age.toMinutes() >= STALENESS_THRESHOLD_MINUTES;
    }

    private static DecimalFormat createPriceFormatter(List<Listing> sellers, List<Listing> buyers) {
        boolean needsDecimals = false;

        if (sellers != null) {
            for (Listing listing : sellers) {
                if (listing.price() % 1 != 0) {
                    needsDecimals = true;
                    break;
                }
            }
        }

        if (!needsDecimals && buyers != null) {
            for (Listing listing : buyers) {
                if (listing.price() % 1 != 0) {
                    needsDecimals = true;
                    break;
                }
            }
        }

        String pattern = needsDecimals ? "#,##0.00" : "#,##0";
        DecimalFormat formatter = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setGroupingUsed(true);
        return formatter;
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

        String pattern = needsDecimals ? "#,##0.00" : "#,##0";
        DecimalFormat formatter = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.ROOT));
        formatter.setGroupingUsed(true);
        return formatter;
    }

    private static String formatCoordinates(BlockPos listingPos) {
        if (listingPos == null) {
            return "coords n/a";
        }
        return String.format(Locale.ROOT, "%d %d %d", listingPos.getX(), listingPos.getY(), listingPos.getZ());
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

    private static String sanitizeNameForCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Waystone";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static final Text PREFIX = Text.literal("[Pricebook]").formatted(Formatting.AQUA);

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

    private static final class ListingFormatter {
        private static MutableText format(int index, Listing listing, String playerDimension, Instant now, DecimalFormat priceFormatter) {
            if (listing == null) {
                return null;
            }

            String owner = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner();
            String priceStr = priceFormatter.format(listing.price());
            int amount = Math.max(0, listing.amount());

            MutableText line = Text.literal("│ ").formatted(Formatting.AQUA);
            line.append(Text.literal("☆").formatted(Formatting.GRAY));
            line.append(Text.literal(priceStr).formatted(Formatting.AQUA));

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

        private static final Text SEPARATOR = Text.literal(" · ").formatted(Formatting.DARK_GRAY);

        private static MutableText separator() {
            return SEPARATOR.copy();
        }

        private static String waypointCommand(BlockPos pos, String dimension, String label) {
            String dimToken = (dimension == null || dimension.isBlank()) ? "_" : dimension;
            return String.format(Locale.ROOT, "/%s %d %d %d %s %s",
                    WAYPOINT_COMMAND_NAME,
                    pos.getX(), pos.getY(), pos.getZ(),
                    dimToken,
                    sanitizeNameForCommand(label));
        }
    }
}
