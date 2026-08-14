package com.birditch.escapefromcraftov.item;

import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.item.ItemService;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;

/// Wires the item domain: registry seeded with the demo catalog plus the item
/// service. All wiring happens during initialization; `start` and `stop` are
/// intentionally no-ops.
public final class ItemModule implements CraftovModule {

  @Override
  public String id() {
    return "item";
  }

  @Override
  public void initialize(CraftovContext context) {
    SimpleItemRegistry registry = new SimpleItemRegistry();
    DefaultItems.registerAll(registry);
    context.services().register(ItemRegistry.class, registry);
    context.services().register(ItemService.class, new DefaultItemService(registry));
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
  }
}
