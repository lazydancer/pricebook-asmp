package com.asmp.pricebook.command;

import com.asmp.pricebook.Pricebook;
import com.asmp.pricebook.command.PricebookQueryService.ItemLookupResult;
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
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class PricebookCommand {
    private static final String WAYPOINT_COMMAND_NAME = "pricebook_waypoint";

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
        future.thenAccept(result -> client.execute(() -> PricebookRenderer.deliverResult(player, result)));

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
        future.thenAccept(result -> client.execute(() -> PricebookRenderer.deliverHistoryResult(player, result)));

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

    private static MutableText prefixed(String message, Formatting formatting) {
        MutableText prefix = Text.literal("[Pricebook]").formatted(Formatting.AQUA).copy();
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
