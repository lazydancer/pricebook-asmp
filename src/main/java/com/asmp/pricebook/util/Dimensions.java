package com.asmp.pricebook.util;

import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.Locale;

/**
 * Centralizes common dimension name normalization so the rest of the codebase
 * speaks the same language when comparing or serializing dimensions.
 */
public final class Dimensions {
    public static final String OVERWORLD = "overworld";
    public static final String NETHER = "nether";
    public static final String END = "end";

    private Dimensions() {
    }

    public static String canonical(World world) {
        if (world == null) {
            return "";
        }
        return canonical(world.getRegistryKey().getValue());
    }

    public static String canonical(Identifier identifier) {
        if (identifier == null) {
            return "";
        }
        return canonical(identifier.toString());
    }

    public static String canonical(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String lower = raw.toLowerCase(Locale.ROOT).trim();
        if (lower.contains("nether")) {
            return NETHER;
        }
        if (lower.contains("end")) {
            return END;
        }
        if (lower.contains("overworld")) {
            return OVERWORLD;
        }
        return raw.trim();
    }

    public static String registryId(String raw) {
        return switch (canonical(raw)) {
            case OVERWORLD -> "minecraft:overworld";
            case NETHER -> "minecraft:the_nether";
            case END -> "minecraft:the_end";
            default -> raw == null ? "" : raw.trim();
        };
    }

    public static boolean same(World world, String other) {
        if (world == null) {
            return false;
        }
        return canonical(world).equals(canonical(other));
    }
}

