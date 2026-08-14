package com.birditch.escapefromcraftov.api.loot;

import java.util.Collection;
import java.util.Optional;

/// Registry of loot tables. Append-only; registration happens at bootstrap
/// on the main thread.
public interface LootTableRegistry {

    /// @throws LootException if a table with the same id is already registered
    void register(LootTable table);

    Optional<LootTable> find(LootTableId id);

    /// @throws LootException if no table with `id` is registered
    LootTable require(LootTableId id);

    Collection<LootTable> all();

    int size();
}
