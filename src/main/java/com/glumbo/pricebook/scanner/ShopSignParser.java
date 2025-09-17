package com.glumbo.pricebook.scanner;

import net.minecraft.block.BlockState;
import net.minecraft.block.SignBlock;
import net.minecraft.block.WallSignBlock;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ShopSignParser {
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("(selling|buying)\\s+(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_PATTERN = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    private ShopSignParser() {
    }

    static Optional<ShopEntry> parse(World world, BlockPos pos, SignBlockEntity sign) {
        BlockState blockState = world.getBlockState(pos);
        if (!(blockState.getBlock() instanceof SignBlock || blockState.getBlock() instanceof WallSignBlock)) {
            return Optional.empty();
        }

        String[] lines = readLines(sign);
        if (lines.length != 4) {
            return Optional.empty();
        }

        String owner = lines[0].trim();
        String statusLine = lines[1].trim();
        String item = lines[2].trim();
        String priceLine = lines[3].trim();

        if (owner.isEmpty() || statusLine.isEmpty() || item.isEmpty() || priceLine.isEmpty()) {
            return Optional.empty();
        }

        String action = resolveAction(statusLine);
        if (action == null) {
            return Optional.empty();
        }

        double price;
        try {
            price = parsePrice(priceLine);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }

        int amount = extractAmount(statusLine);
        return Optional.of(new ShopEntry(owner, item, pos.toImmutable(), price, amount, action));
    }

    static record ShopEntry(String owner, String item, BlockPos position, double price, int amount, String action) {
    }

    private static String[] readLines(SignBlockEntity sign) {
        SignText front = sign.getFrontText();
        return Arrays.stream(front.getMessages(false))
                .map(Text::getString)
                .toArray(String[]::new);
    }

    private static int extractAmount(String statusLine) {
        Matcher matcher = AMOUNT_PATTERN.matcher(statusLine);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(2));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0;
    }

    private static double parsePrice(String priceLine) {
        String normalized = priceLine.replace(",", "");
        Matcher matcher = PRICE_PATTERN.matcher(normalized);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        throw new NumberFormatException("Cannot parse price from line: " + priceLine);
    }

    private static String resolveAction(String statusLine) {
        String lower = statusLine.toLowerCase(Locale.ROOT);
        if (lower.contains("selling")) {
            return "sell";
        }
        if (lower.contains("buying")) {
            return "buy";
        }
        if (lower.contains("out of stock") || lower.contains("out-of-stock") || lower.contains("outofstock")) {
            return "out of stock";
        }
        return null;
    }
}
