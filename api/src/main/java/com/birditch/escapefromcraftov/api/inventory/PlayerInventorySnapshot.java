package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.UUID;

/// Immutable view of every durable item location for one player.
public record PlayerInventorySnapshot(UUID playerId, List<StoredItem> items) {

  public PlayerInventorySnapshot {
    Preconditions.checkNotNull(playerId, "playerId");
    items = List.copyOf(Preconditions.checkNotNull(items, "items"));
    Preconditions.checkArgument(items.stream().allMatch(item -> item != null),
        "items must not contain null");
  }

  public List<StoredItem> at(InventoryLocation location) {
    Preconditions.checkNotNull(location, "location");
    return items.stream().filter(item -> item.location() == location).toList();
  }
}
