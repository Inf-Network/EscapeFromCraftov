package com.birditch.raidcore.item;

import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.api.item.ItemService;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;

/// Wires the item domain: registry seeded with the demo catalog plus the item
/// service. All wiring happens during initialization; `start` and `stop` are
/// intentionally no-ops.
public final class ItemModule implements RaidModule {

  @Override
  public String id() {
    return "item";
  }

  @Override
  public void initialize(RaidContext context) {
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
