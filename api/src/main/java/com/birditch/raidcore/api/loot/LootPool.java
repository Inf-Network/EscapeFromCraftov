package com.birditch.raidcore.api.loot;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// A pool rolled `rolls` times; each roll picks one entry by weight.
public record LootPool(int rolls, List<LootEntry> entries) {

    public LootPool {
        Preconditions.checkArgument(rolls >= 1, "rolls must be at least 1");
        Preconditions.checkNotNull(entries, "entries");
        Preconditions.checkArgument(!entries.isEmpty(), "entries must not be empty");
        entries = List.copyOf(entries);
    }
}
