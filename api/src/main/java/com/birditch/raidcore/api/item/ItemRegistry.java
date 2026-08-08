package com.birditch.raidcore.api.item;

import java.util.Collection;
import java.util.Optional;

/// Registry of item definitions. Append-only; registration happens at bootstrap
/// on the main thread.
public interface ItemRegistry {

    /// @throws com.birditch.raidcore.api.RaidException if an item with the same id is already registered
    void register(ItemDefinition definition);

    Optional<ItemDefinition> find(ItemId id);

    /// @throws com.birditch.raidcore.api.RaidException if no item with `id` is registered
    ItemDefinition require(ItemId id);

    Collection<ItemDefinition> all();

    int size();
}
