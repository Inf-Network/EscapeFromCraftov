package net.infnetwork.snowball.craftov.loot;

import net.infnetwork.snowball.craftov.item.Category;
import net.infnetwork.snowball.craftov.item.Rarity;

import java.util.Map;

/** A kind of searchable container: its look, size and what it tends to hold. */
public record ContainerType(
        String id,
        String name,
        String model,
        float scale,
        double hitboxWidth,
        double hitboxHeight,
        int rows,
        int minItems,
        int maxItems,
        double searchSpeed,
        Map<Category, Double> categories,
        Map<Rarity, Double> rarities
) {
}
