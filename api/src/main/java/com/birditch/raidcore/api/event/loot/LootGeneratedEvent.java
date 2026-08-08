package com.birditch.raidcore.api.event.loot;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// Published after a loot table roll; `items` is stored as an immutable copy.
public record LootGeneratedEvent(LootTableId tableId, List<ItemInstance> items) implements RaidEvent {

    public LootGeneratedEvent {
        Preconditions.checkNotNull(tableId, "tableId");
        Preconditions.checkNotNull(items, "items");
        items = List.copyOf(items);
    }
}
