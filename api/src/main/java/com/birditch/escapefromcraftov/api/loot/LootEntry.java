package com.birditch.escapefromcraftov.api.loot;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// One weighted candidate of a loot pool with an inclusive quantity range.
public record LootEntry(ItemId itemId, int weight, int minQuantity, int maxQuantity) {

    public LootEntry {
        Preconditions.checkNotNull(itemId, "itemId");
        Preconditions.checkArgument(weight > 0, "weight must be greater than 0");
        Preconditions.checkArgument(minQuantity >= 1, "minQuantity must be at least 1");
        Preconditions.checkArgument(minQuantity <= maxQuantity,
                "minQuantity must not exceed maxQuantity");
    }
}
