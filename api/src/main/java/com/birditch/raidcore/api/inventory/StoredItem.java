package com.birditch.raidcore.api.inventory;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// One durable owned stack. RAID rows are bound to exactly one raid id.
public record StoredItem(
    UUID storageId,
    ItemId itemId,
    int quantity,
    InventoryLocation location,
    @Nullable RaidGameId raidId) {

  public StoredItem {
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    Preconditions.checkNotNull(location, "location");
    Preconditions.checkArgument(location == InventoryLocation.RAID == (raidId != null),
        "raidId must be present exactly for RAID items");
  }

  public Optional<RaidGameId> currentRaid() {
    return Optional.ofNullable(raidId);
  }
}
