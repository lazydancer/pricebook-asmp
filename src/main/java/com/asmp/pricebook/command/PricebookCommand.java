package com.asmp.pricebook.command;

import com.asmp.pricebook.PricebookClient;
import com.asmp.pricebook.command.PricebookQueryService.ItemInfo;
import com.asmp.pricebook.command.PricebookQueryService.ItemLookupResult;
import com.asmp.pricebook.command.PricebookQueryService.Listing;
import com.asmp.pricebook.command.PricebookQueryService.WaystoneReference;
import com.asmp.pricebook.integration.WaypointHelper;
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
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class PricebookCommand {
    private static final DecimalFormat PRICE_FORMAT = new DecimalFormat("0.###",
            DecimalFormatSymbols.getInstance(Locale.ROOT));
    private static final String WAYPOINT_COMMAND_NAME = "pricebook_waypoint";

    static {
        PRICE_FORMAT.setGroupingUsed(false);
    }

    private PricebookCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("pricebook")
                    .executes(ctx -> execute(ctx.getSource(), null))
                    .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                            .suggests(PricebookCommand::suggestItems)
                            .executes(ctx -> execute(ctx.getSource(), StringArgumentType.getString(ctx, "item")))));

            dispatcher.register(ClientCommandManager.literal("pb")
                    .executes(ctx -> execute(ctx.getSource(), null))
                    .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                            .suggests(PricebookCommand::suggestItems)
                            .executes(ctx -> execute(ctx.getSource(), StringArgumentType.getString(ctx, "item")))));

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

        WaypointHelper.clear(); // Always clear the waypoint when running command

        if (!PricebookClient.isEnabled()) {
            player.sendMessage(Text.literal("[Pricebook] Not connected to asmp.cc.").formatted(Formatting.RED), false);
            return 1;
        }

        PricebookQueryService service = PricebookClient.pricebookQueryService();
        if (service == null) {
            player.sendMessage(Text.literal("[Pricebook] Query service not available.").formatted(Formatting.RED), false);
            return 1;
        }

        String resolved = resolveItemName(player, itemName);
        if (resolved == null || resolved.isBlank()) {
            player.sendMessage(Text.literal("[Pricebook] Hold an item or specify a name.").formatted(Formatting.RED), false);
            return 1;
        }

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
        List<String> catalog = PricebookClient.itemCatalog();
        if (catalog == null || catalog.isEmpty()) {
            return builder.buildFuture();
        }
        return CommandSource.suggestMatching(catalog, builder);
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

        String dimension = normalizeDimension(dimensionArg);
        if (dimension.isEmpty() || dimension.equals("_")) {
            dimension = dimensionName(player.getWorld());
        }

        BlockPos pos = new BlockPos(x, y, z);
        boolean success = WaypointHelper.createWaypoint(pos, dimension, name);
        if (success) {
            player.sendMessage(Text.literal(String.format(Locale.ROOT,
                    "[Pricebook] Created waypoint '%s'. Run /pb again to clear",
                    name == null || name.isBlank() ? "Waystone" : name)).formatted(Formatting.AQUA), false);
            return 1;
        }

        player.sendMessage(Text.literal("[Pricebook] Unable to create waypoint."
        ).formatted(Formatting.RED), false);
        return 0;
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

        String itemName = toTitleCase(info.itemName() == null || info.itemName().isBlank() ? "Unknown item" : info.itemName());

        MutableText header = Text.literal("").append(Text.literal("━━━━━━━━ ").formatted(Formatting.DARK_GRAY))
                .append(Text.literal("[Pricebook] ").formatted(Formatting.AQUA))
                .append(Text.literal(itemName).formatted(Formatting.AQUA))
                .append(Text.literal(" ━━━━━━━━").formatted(Formatting.DARK_GRAY));
        player.sendMessage(header, false);

        List<Listing> sellers = info.topSellers();
        List<Listing> buyers = info.topBuyers();

        boolean noSellers = sellers == null || sellers.isEmpty();
        boolean noBuyers = buyers == null || buyers.isEmpty();

        if (noSellers && noBuyers) {
            player.sendMessage(Text.literal("No buyers or sellers yet.").formatted(Formatting.GRAY), false);
            return;
        }

        sendList(player, "Sellers", sellers);

        if (!noBuyers) {
            sendList(player, "Buyers", buyers);
        }
    }

    private static void sendList(ClientPlayerEntity player, String title, List<Listing> entries) {
        if (entries == null || entries.isEmpty()) {
            return;
        }

        player.sendMessage(Text.literal(title).formatted(Formatting.AQUA), false);

        String playerDimension = dimensionName(player.getWorld());
        Instant now = Instant.now();

        int limit = Math.min(3, entries.size());
        for (int i = 0; i < limit; i++) {
            Listing listing = entries.get(i);
            String owner = listing.owner() == null || listing.owner().isBlank() ? "Unknown" : listing.owner();

            String priceStr = PRICE_FORMAT.format(listing.price());
            int amount = Math.max(0, listing.amount());

            final Text separator = Text.literal(" · ").formatted(Formatting.DARK_GRAY);

            MutableText line = Text.literal(String.format(Locale.ROOT, " %d ", i + 1)).formatted(Formatting.DARK_GRAY);
            line.append(separator).append(Text.literal(priceStr).formatted(Formatting.AQUA));

            MutableText quantityPart = Text.literal("[").formatted(Formatting.GRAY)
                    .append(Text.literal(String.valueOf(amount)).formatted(Formatting.AQUA))
                    .append(Text.literal("]").formatted(Formatting.GRAY));
            line.append(separator).append(quantityPart);

            line.append(separator).append(Text.literal(owner).formatted(Formatting.GRAY));

            String dimension = normalizeDimension(listing.dimension());
            String highlightDimension = (dimension.isEmpty() ? playerDimension : dimension);

            WaystoneReference waystone = listing.nearestWaystone();
            if (waystone != null && waystone.position() != null) {
                BlockPos wsPos = waystone.position();
                String wsName = waystone.name() == null || waystone.name().isBlank()
                        ? "Waystone"
                        : waystone.name();
                String dimensionArg = highlightDimension.isEmpty() ? playerDimension : highlightDimension;
                if (dimensionArg == null) {
                    dimensionArg = "";
                }
                String wsCommand = String.format(Locale.ROOT, "/%s %d %d %d %s %s",
                        WAYPOINT_COMMAND_NAME,
                        wsPos.getX(), wsPos.getY(), wsPos.getZ(),
                        dimensionArg.isEmpty() ? "_" : dimensionArg,
                        sanitizeNameForCommand(wsName));

                MutableText wsLink = Text.literal(wsName)
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.RunCommand(wsCommand))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to create waypoint at " + wsName))));

                line.append(separator).append(wsLink);
            }

            BlockPos listingPos = listing.position();
            if (listingPos != null) {
                String waypointName = String.format("%s's Shop", owner);
                String command = String.format(Locale.ROOT, "/%s %d %d %d %s %s",
                        WAYPOINT_COMMAND_NAME, listingPos.getX(), listingPos.getY(), listingPos.getZ(),
                        highlightDimension.isEmpty() ? "_" : highlightDimension, sanitizeNameForCommand(waypointName));
                MutableText coordsLink = Text.literal(formatCoordinates(listingPos))
                        .formatted(Formatting.GRAY)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent.RunCommand(command))
                                .withHoverEvent(new HoverEvent.ShowText(
                                        Text.literal("Click to create waypoint at shop"))));
                line.append(separator).append(coordsLink);
            }

            if (!dimension.isEmpty() && !dimension.equals(playerDimension)) {
                line.append(Text.literal(" (" + dimension + ")").formatted(Formatting.DARK_AQUA));
            }

            if (isStale(now, listing.lastSeenAt())) {
                line.append(Text.literal(" [Stale]").formatted(Formatting.RED));
            }

            player.sendMessage(line, false);
        }
    }

    private static boolean isStale(Instant now, Instant lastSeen) {
        if (lastSeen == null || lastSeen.equals(Instant.EPOCH)) {
            return true;
        }
        Duration age = Duration.between(lastSeen, now).abs();
        return age.toMinutes() >= 60 * 24;
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

    private static String sanitizeNameForCommand(String raw) {
        if (raw == null || raw.isBlank()) {
            return "Waystone";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }
}