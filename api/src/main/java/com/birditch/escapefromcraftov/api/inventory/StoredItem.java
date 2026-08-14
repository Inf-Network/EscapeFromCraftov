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
    UUID instanceId,
    ItemId itemId,
    int quantity,
    InventoryLocation location,
    @Nullable CraftovGameId craftovId,
    @Nullable String opaquePayload) {

  public StoredItem {
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    Preconditions.checkNotNull(location, "location");
    Preconditions.checkArgument(location == InventoryLocation.CRAFTOV == (craftovId != null),
        "craftovId must be present exactly for CRAFTOV items");
  }

  /// Compatibility constructor for rows created before durable item instances were stored.
  /// Their storage id is the only stable historical identity and therefore becomes the
  /// instance id on first load.
  public StoredItem(UUID storageId, ItemId itemId, int quantity,
      InventoryLocation location, @Nullable CraftovGameId craftovId) {
    this(storageId, storageId, itemId, quantity, location, craftovId, null);
  }

  public Optional<CraftovGameId> currentCraftov() {
    return Optional.ofNullable(craftovId);
  }

  public Optional<String> payload() {
    return Optional.ofNullable(opaquePayload);
  }
}
