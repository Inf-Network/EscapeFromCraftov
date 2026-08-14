package com.birditch.escapefromcraftov.item;

import com.birditch.escapefromcraftov.api.CraftovException;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.item.ItemService;
import com.birditch.escapefromcraftov.common.check.Preconditions;
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
      throw new CraftovException("Quantity for item " + id.value() + " must be within 1.."
          + definition.stackSize() + " but was: " + quantity);
    }
    return new ItemInstance(UUID.randomUUID(), id, quantity);
  }

  @Override
  public ItemRegistry registry() {
    return registry;
  }
}
