package com.birditch.escapefromcraftov.api.item;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// A concrete stack of an item type; `quantity` must be at least 1.
///
/// `instanceId` is the durable identity of the physical stack. It is deliberately
/// distinct from database row ids and must survive warehouse, craftov and extraction
/// transitions. `opaquePayload` is an adapter-owned representation of the concrete
/// item state (for example Bukkit item data); the API never interprets it.
public record ItemInstance(
    UUID instanceId,
    ItemId itemId,
    int quantity,
    @Nullable String opaquePayload) {

  public ItemInstance {
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be at least 1");
  }

  /// Compatibility constructor for definitions and old callers without concrete item data.
  public ItemInstance(UUID instanceId, ItemId itemId, int quantity) {
    this(instanceId, itemId, quantity, null);
  }

  public Optional<String> payload() {
    return Optional.ofNullable(opaquePayload);
  }
}
