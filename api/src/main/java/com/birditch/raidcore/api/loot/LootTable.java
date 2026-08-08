package com.birditch.raidcore.api.loot;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// Immutable loot table composed of one or more pools.
public record LootTable(LootTableId id, List<LootPool> pools) {

    public LootTable {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(pools, "pools");
        Preconditions.checkArgument(!pools.isEmpty(), "pools must not be empty");
        pools = List.copyOf(pools);
    }
}
