package com.birditch.raidcore.api.item;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// A concrete stack of an item type; `quantity` must be at least 1.
public record ItemInstance(UUID instanceId, ItemId itemId, int quantity) {

    public ItemInstance {
        Preconditions.checkNotNull(instanceId, "instanceId");
        Preconditions.checkNotNull(itemId, "itemId");
        Preconditions.checkArgument(quantity >= 1, "quantity must be at least 1");
    }
}
