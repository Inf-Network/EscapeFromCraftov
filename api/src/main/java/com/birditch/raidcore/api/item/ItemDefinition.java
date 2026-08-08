package com.birditch.raidcore.api.item;

import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.common.check.Preconditions;

/// Immutable static definition of an item type.
public record ItemDefinition(
        ItemId id,
        String displayName,
        ItemCategory category,
        ItemRarity rarity,
        Money basePrice,
        int weightGrams,
        int stackSize,
        boolean tradable,
        boolean sellable,
        boolean droppable) {

    public ItemDefinition {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
        Preconditions.checkNotNull(category, "category");
        Preconditions.checkNotNull(rarity, "rarity");
        Preconditions.checkNotNull(basePrice, "basePrice");
        Preconditions.checkArgument(weightGrams >= 0, "weightGrams must not be negative");
        Preconditions.checkArgument(stackSize >= 1, "stackSize must be at least 1");
    }

    /// Builder with defaults: basePrice [Money#ZERO], weightGrams 0, stackSize 1,
    /// tradable/sellable/droppable all `true`.
    public static Builder builder(ItemId id, String displayName, ItemCategory category, ItemRarity rarity) {
        return new Builder(id, displayName, category, rarity);
    }

    public static final class Builder {

        private final ItemId id;
        private final String displayName;
        private final ItemCategory category;
        private final ItemRarity rarity;
        private Money basePrice = Money.ZERO;
        private int weightGrams;
        private int stackSize = 1;
        private boolean tradable = true;
        private boolean sellable = true;
        private boolean droppable = true;

        private Builder(ItemId id, String displayName, ItemCategory category, ItemRarity rarity) {
            this.id = id;
            this.displayName = displayName;
            this.category = category;
            this.rarity = rarity;
        }

        public Builder basePrice(Money basePrice) {
            this.basePrice = basePrice;
            return this;
        }

        public Builder weightGrams(int weightGrams) {
            this.weightGrams = weightGrams;
            return this;
        }

        public Builder stackSize(int stackSize) {
            this.stackSize = stackSize;
            return this;
        }

        public Builder tradable(boolean tradable) {
            this.tradable = tradable;
            return this;
        }

        public Builder sellable(boolean sellable) {
            this.sellable = sellable;
            return this;
        }

        public Builder droppable(boolean droppable) {
            this.droppable = droppable;
            return this;
        }

        public ItemDefinition build() {
            return new ItemDefinition(id, displayName, category, rarity, basePrice,
                    weightGrams, stackSize, tradable, sellable, droppable);
        }
    }
}
