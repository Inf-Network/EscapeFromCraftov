package com.birditch.escapefromcraftov.api.loot;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.List;

/// Rolls loot tables into concrete item instances. Main-thread only.
public interface LootService {

    /// @throws LootException if `tableId` is not registered
    List<ItemInstance> roll(LootTableId tableId, LootRollContext context);
}
