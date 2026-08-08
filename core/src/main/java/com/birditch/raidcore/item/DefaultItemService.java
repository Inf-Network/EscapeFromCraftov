package com.birditch.raidcore.item;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.item.ItemService;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Default [ItemService] backed by an [ItemRegistry].
public final class DefaultItemService implements ItemService {

  private final ItemRegistry registry;

  public DefaultItemService(ItemRegistry registry) {
    this.registry = Preconditions.checkNotNull(registry, "registry");
  }

  @Override
  public ItemInstance createInstance(ItemId id, int quantity) {
    Preconditions.checkNotNull(id, "id");
    ItemDefinition definition = registry.require(id);
    if (quantity < 1 || quantity > definition.stackSize()) {
      throw new RaidException("Quantity for item " + id.value() + " must be within 1.."
          + definition.stackSize() + " but was: " + quantity);
    }
    return new ItemInstance(UUID.randomUUID(), id, quantity);
  }

  @Override
  public ItemRegistry registry() {
    return registry;
  }
}
