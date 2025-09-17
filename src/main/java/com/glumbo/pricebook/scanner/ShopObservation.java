package com.glumbo.pricebook.scanner;

import net.minecraft.util.math.BlockPos;

import java.util.Objects;

public record ShopObservation(
        String owner,
        String item,
        BlockPos position,
        double price,
        int amount,
        String dimension,
        ShopAction action
) {
    public ShopObservation {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(action, "action");
    }

    public int chunkX() {
        return position.getX() >> 4;
    }

    public int chunkZ() {
        return position.getZ() >> 4;
    }

    public int[] asPositionArray() {
        return new int[]{position.getX(), position.getY(), position.getZ()};
    }
}
