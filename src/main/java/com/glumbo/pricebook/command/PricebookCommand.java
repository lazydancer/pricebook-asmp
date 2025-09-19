package com.glumbo.pricebook.command;

import com.glumbo.pricebook.GlumboPricebookClient;
import com.glumbo.pricebook.client.ShopHighlighter;
import com.glumbo.pricebook.command.PricebookQueryService.ItemInfo;
import com.glumbo.pricebook.command.PricebookQueryService.ItemLookupResult;
import com.glumbo.pricebook.command.PricebookQueryService.Listing;
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
import net.minecraft.world.World;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PricebookCommand {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")
            .withLocale(Locale.ROOT)
            .withZone(ZoneId.systemDefault());
    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("0.###",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final String HIGHLIGHT_COMMAND_NAME = "pricebook_mark";

    static {
        PRICE_FORMAT.setGroupingUsed(false);
    }

    private PricebookCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("pricebook")
                    .executes(ctx -> execute(ctx.getSource(), null))
                    .then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .suggests(PricebookCommand::suggestItems)
                            .executes(ctx -> execute(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item")))));

            dispatcher.register(ClientCommandManager.literal("pb")
                    .executes(ctx -> execute(ctx.getSource(), null))
                    .then(ClientCommandManager.argument("item", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                            .suggests(PricebookCommand::suggestItems)
                            .executes(ctx -> execute(ctx.getSource(), com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "item")))));

            dispatcher.register(ClientCommandManager.literal(HIGHLIGHT_COMMAND_NAME)
                    .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                                    .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                                            .executes(ctx -> highlight(ctx.getSource(),
                                                    IntegerArgumentType.getInteger(ctx, "x"),
                                                    IntegerArgumentType.getInteger(ctx, "y"),
                                                    IntegerArgumentType.getInteger(ctx, "z"),
                                                    null))
                                            .then(ClientCommandManager.argument("dimension", StringArgumentType.word())
                                                    .executes(ctx -> highlight(ctx.getSource(),
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "y"),
                                                            IntegerArgumentType.getInteger(ctx, "z"),
                                                            StringArgumentType.getString(ctx, "dimension"))))))));
        });
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

        if (!GlumboPricebookClient.isEnabled()) {
            player.sendMessage(Text.literal("[Pricebook] Not connected to asmp.cc.").formatted(Formatting.RED), false);
            return 1;
        }

        PricebookQueryService service = GlumboPricebookClient.pricebookQueryService();
        if (service == null) {
            player.sendMessage(Text.literal("[Pricebook] Query service not available.").formatted(Formatting.RED), false);
            return 1;
        }

        String resolved = resolveItemName(player, itemName);
        if (resolved == null || resolved.isBlank()) {
            player.sendMessage(Text.literal("[Pricebook] Hold an item or specify a name.").formatted(Formatting.RED), false);
            return 1;
        }

        player.sendMessage(Text.literal("[Pricebook] Looking up " + resolved + "…").formatted(Formatting.GRAY), false);

        CompletableFuture<ItemLookupResult> future = service.lookup(resolved);
        future.thenAccept(result -> client.execute(() -> deliverResult(player, result)));

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
        List<String> catalog = GlumboPricebookClient.itemCatalog();
        if (catalog == null || catalog.isEmpty()) {
            return builder.buildFuture();
        }
        return CommandSource.suggestMatching(catalog, builder);
    }

    private static int highlight(FabricClientCommandSource source, int x, int y, int z, String dimensionArg) {
        MinecraftClient client = source.getClient();
        if (client == null) {
            return 0;
        }

        ClientPlayerEntity player = client.player;
        if (player == null) {
            return 0;
        }

        if (!GlumboPricebookClient.isEnabled()) {
            player.sendMessage(Text.literal("[Pricebook] Not connected to asmp.cc.").formatted(Formatting.RED), false);
            return 1;
        }

        String playerDimension = dimensionName(player.getWorld());
        String normalizedArg = dimensionArg == null || dimensionArg.isBlank()
                ? playerDimension
                : normalizeDimension(dimensionArg);
        if (normalizedArg.isEmpty()) {
            normalizedArg = playerDimension;
        }

        boolean visible = ShopHighlighter.highlight(new BlockPos(x, y, z), normalizedArg);

        String dimensionSuffix = normalizedArg.isEmpty() ? "" : " (" + normalizedArg + ")";
        if (visible) {
            player.sendMessage(Text.literal(String.format(Locale.ROOT,
                    "[Pricebook] Highlighting %d %d %d%s", x, y, z, dimensionSuffix)).formatted(Formatting.AQUA), false);
        } else {
            player.sendMessage(Text.literal(String.format(Locale.ROOT,
                    "[Pricebook] Highlight ready at %d %d %d%s. Switch dimension to view.",
                    x, y, z, dimensionSuffix)).formatted(Formatting.GRAY), false);
        }

        return 1;
    }

    private static void deliverResult(ClientPlayerEntity playerRef, ItemLookupResult result) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client != null ? client.player : null;
        if (player == null || playerRef == null || !Objects.equals(player.getUuid(), playerRef.getUuid())) {
            return;
        }

        if (result == null) {
            player.sendMessage(Text.literal("[Pricebook] No response.").formatted(Formatting.RED), false);
            return;
        }

        if (!result.isSuccess()) {
            String message = Objects.requireNonNullElse(result.error(), "Unknown error.");
            player.sendMessage(Text.literal("[Pricebook] " + message).formatted(Formatting.RED), false);
            return;
        }

        ItemInfo info = result.info();
        if (info == null) {
            player.sendMessage(Text.literal("[Pricebook] Unknown error.").formatted(Formatting.RED), false);
            return;
        }

        String itemName = info.itemName() == null || info.itemName().isBlank() ? "Unknown item" : info.itemName();
        Instant refreshedAt = info.refreshedAt();
        String refreshed = refreshedAt == null || refreshedAt.equals(Instant.EPOCH)
                ? "unknown"
                : TIME_FORMATTER.format(refreshedAt);

        String header = String.format(Locale.ROOT, "[Pricebook] %s · refreshed %s", itemName, refreshed);
        player.sendMessage(Text.literal(header).formatted(Formatting.GOLD), false);

        List<Listing> sellers = info.topSellers();
        List<Listing> buyers = info.topBuyers();

        boolean noSellers = sellers == null || sellers.isEmpty();
        boolean noBuyers = buyers == null || buyers.isEmpty();

        if (noSellers && noBuyers) {
            player.sendMessage(Text.literal("[Pricebook] No buyers or sellers yet.").formatted(Formatting.GRAY), false);
            return;
        }

        sendList(player, "Top Sellers", sellers, true);
        sendList(player, "Top Buyers", buyers, false);
    }

    private static void sendList(ClientPlayerEntity player, String title, List<Listing> entries, boolean seller) {
        player.sendMessage(Text.literal(title).formatted(Formatting.YELLOW), false);

        if (entries == null || entries.isEmpty()) {
            player.sendMessage(Text.literal("  none").formatted(Formatting.DARK_GRAY), false);
            return;
        }

        String playerDimension = dimensionName(player.getWorld());
        Instant now = Instant.now();

        int limit = Math.min(3, entries.size());
        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            String owner = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner();
            MutableText line = Text.literal(String.format(Locale.ROOT, "%d) %s — %s ", i + 1,
                    owner, PRICE_FORMAT.format(listing.price())));

            String stockOrNeed = seller ? formatStock(listing.amount()) : formatDemand(listing.amount());
            line.append(Text.literal(stockOrNeed).formatted(Formatting.AQUA));

            String dimension = normalizeDimension(listing.dimension());

            BlockPos listingPos = listing.position();
            String coordsDisplay = formatCoordinates(listingPos);
            String highlightDimension = (dimension.isEmpty() ? playerDimension : dimension);
            MutableText coordsText = Text.literal(" · " + coordsDisplay)
                    .formatted(Formatting.GRAY);
            if (listingPos != null) {
                String command = highlightDimension.isEmpty()
                        ? String.format(Locale.ROOT, "/%s %d %d %d",
                        HIGHLIGHT_COMMAND_NAME, listingPos.getX(), listingPos.getY(), listingPos.getZ())
                        : String.format(Locale.ROOT, "/%s %d %d %d %s",
                        HIGHLIGHT_COMMAND_NAME, listingPos.getX(), listingPos.getY(), listingPos.getZ(), highlightDimension);
                MutableText coordsLink = Text.literal(coordsDisplay)
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withUnderline(true)
                                .withClickEvent(new ClickEvent.RunCommand(command))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to highlight location"))));
                coordsText = Text.literal(" · ").formatted(Formatting.GRAY).append(coordsLink);
            }
            line.append(coordsText);

            if (!dimension.isEmpty() && !dimension.equals(playerDimension)) {
                line.append(Text.literal(" · " + dimension).formatted(Formatting.DARK_AQUA));
            }

            if (isStale(now, listing.lastSeenAt())) {
                line.append(Text.literal(" · ").formatted(Formatting.GRAY));
                line.append(Text.literal("⚠ stale").formatted(Formatting.RED));
            }

            player.sendMessage(line, false);
        }
    }

    private static boolean isStale(Instant now, Instant lastSeen) {
        if (lastSeen == null || lastSeen.equals(Instant.EPOCH)) {
            return true;
        }
        Duration age = Duration.between(lastSeen, now).abs();
        return age.toMinutes() >= 5;
    }

    private static String formatCoordinates(BlockPos listingPos) {
        if (listingPos == null) {
            return "coords n/a";
        }
        return String.format(Locale.ROOT, "%d %d %d", listingPos.getX(), listingPos.getY(), listingPos.getZ());
    }

    private static String formatStock(int amount) {
        return "(stock " + formatAmount(amount) + ")";
    }

    private static String formatDemand(int amount) {
        return "(needs " + formatAmount(amount) + ")";
    }

    private static String formatAmount(int amount) {
        double value = Math.abs(amount);
        if (value >= 1_000_000) {
            double millions = value / 1_000_000.0;
            return trimSuffix(String.format(Locale.ROOT, "%.1fm", millions));
        }
        if (value >= 1000) {
            double thousands = value / 1000.0;
            return trimSuffix(String.format(Locale.ROOT, "%.1fk", thousands));
        }
        return Integer.toString(Math.max(amount, 0));
    }

    private static String trimSuffix(String formatted) {
        if (formatted.endsWith(".0k") || formatted.endsWith(".0m")) {
            return formatted.replace(".0", "");
        }
        return formatted;
    }

    private static String dimensionName(World world) {
        if (world == null) {
            return "";
        }
        return normalizeDimension(world.getRegistryKey().getValue().toString());
    }

    private static String normalizeDimension(String raw) {
        if (raw == null) {
            return "";
        }
        return switch (raw) {
            case "minecraft:the_nether", "nether" -> "nether";
            case "minecraft:the_end", "end" -> "end";
            case "minecraft:overworld", "overworld" -> "overworld";
            default -> raw;
        };
    }
}
