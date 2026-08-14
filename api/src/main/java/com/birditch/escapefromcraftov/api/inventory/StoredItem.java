package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// One durable owned stack. CRAFTOV rows are bound to exactly one craftov id.
public record StoredItem(
    UUID storageId,
    ItemId itemId,
    int quantity,
    InventoryLocation location,
    @Nullable CraftovGameId craftovId) {

  public StoredItem {
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    Preconditions.checkNotNull(location, "location");
    Preconditions.checkArgument(location == InventoryLocation.CRAFTOV == (craftovId != null),
        "craftovId must be present exactly for CRAFTOV items");
  }

  public Optional<CraftovGameId> currentCraftov() {
    return Optional.ofNullable(craftovId);
  }
}
