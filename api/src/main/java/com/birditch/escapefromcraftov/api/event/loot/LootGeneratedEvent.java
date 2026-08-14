package com.birditch.escapefromcraftov.api.event.loot;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.loot.LootTableId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// Published after a loot table roll; `items` is stored as an immutable copy.
public record LootGeneratedEvent(LootTableId tableId, List<ItemInstance> items) implements CraftovEvent {

    public LootGeneratedEvent {
        Preconditions.checkNotNull(tableId, "tableId");
        Preconditions.checkNotNull(items, "items");
        items = List.copyOf(items);
    }
}
