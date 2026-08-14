package com.birditch.escapefromcraftov.api.loot;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// A placed loot container bound to a loot table.
public record LootContainer(UUID containerId, LootContainerType type, LootTableId tableId) {

    public LootContainer {
        Preconditions.checkNotNull(containerId, "containerId");
        Preconditions.checkNotNull(type, "type");
        Preconditions.checkNotNull(tableId, "tableId");
    }
}
