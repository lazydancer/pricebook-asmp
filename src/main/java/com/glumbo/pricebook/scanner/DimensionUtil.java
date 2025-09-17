package com.glumbo.pricebook.scanner;

import net.minecraft.util.Identifier;
import net.minecraft.world.World;

final class DimensionUtil {
    private DimensionUtil() {
    }

    static String lookup(World world) {
        Identifier key = world.getRegistryKey().getValue();
        return switch (key.toString()) {
            case "minecraft:the_nether" -> "nether";
            case "minecraft:the_end" -> "end";
            default -> "overworld";
        };
    }
}
