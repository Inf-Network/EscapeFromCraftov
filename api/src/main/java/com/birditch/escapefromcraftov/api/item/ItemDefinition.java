package com.birditch.escapefromcraftov.api.item;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.common.check.Preconditions;

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
        boolean droppable,
        int containerSlots,
        boolean secureAllowed) {

    public ItemDefinition {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
        Preconditions.checkNotNull(category, "category");
        Preconditions.checkNotNull(rarity, "rarity");
        Preconditions.checkNotNull(basePrice, "basePrice");
        Preconditions.checkArgument(weightGrams >= 0, "weightGrams must not be negative");
        Preconditions.checkArgument(stackSize >= 1, "stackSize must be at least 1");
        boolean container = category == ItemCategory.CHEST_RIG
                || category == ItemCategory.BACKPACK;
        Preconditions.checkArgument(container
                        ? containerSlots >= 1 && containerSlots <= 27
                        : containerSlots == 0,
                "chest rigs/backpacks require 1..27 container slots; other items require 0");
    }

    /// Binary/source-compatible constructor retained for definitions without carry metadata.
    public ItemDefinition(ItemId id, String displayName, ItemCategory category,
            ItemRarity rarity, Money basePrice, int weightGrams, int stackSize,
            boolean tradable, boolean sellable, boolean droppable) {
        this(id, displayName, category, rarity, basePrice, weightGrams, stackSize,
                tradable, sellable, droppable, 0, true);
    }

    /// Source-compatible constructor retained for definitions written before secure-box rules.
    public ItemDefinition(ItemId id, String displayName, ItemCategory category,
            ItemRarity rarity, Money basePrice, int weightGrams, int stackSize,
            boolean tradable, boolean sellable, boolean droppable, int containerSlots) {
        this(id, displayName, category, rarity, basePrice, weightGrams, stackSize,
                tradable, sellable, droppable, containerSlots, true);
    }

    /// Builder with defaults: basePrice [Money#ZERO], weightGrams/containerSlots 0,
    /// stackSize 1, tradable/sellable/droppable/secureAllowed all `true`.
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
        private int containerSlots;
        private boolean secureAllowed = true;

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

        /// Physical cargo cells unlocked while this chest rig/backpack is equipped.
        public Builder containerSlots(int containerSlots) {
            this.containerSlots = containerSlots;
            return this;
        }

        /// Whether this item type may be stored in the built-in secure container.
        public Builder secureAllowed(boolean secureAllowed) {
            this.secureAllowed = secureAllowed;
            return this;
        }

        public ItemDefinition build() {
            return new ItemDefinition(id, displayName, category, rarity, basePrice,
                    weightGrams, stackSize, tradable, sellable, droppable, containerSlots,
                    secureAllowed);
        }
    }
}
