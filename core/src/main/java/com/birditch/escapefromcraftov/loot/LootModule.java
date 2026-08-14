package com.birditch.escapefromcraftov.loot;

import com.birditch.escapefromcraftov.api.item.ItemService;
import com.birditch.escapefromcraftov.api.loot.LootService;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.common.random.ThreadLocalRandomProvider;
import com.birditch.escapefromcraftov.core.CraftovContext;
import com.birditch.escapefromcraftov.core.module.CraftovModule;

/// Wires the loot domain: table registry, roll service and the built-in debug
/// tables. Requires the item module to be initialized first, because
/// [ItemService] is resolved from the service registry. All wiring happens in
/// `initialize`; `start` and `stop` have no behavior.
public final class LootModule implements CraftovModule {

  public LootModule() {
  }

  @Override
  public String id() {
    return "loot";
  }

  @Override
  public void initialize(CraftovContext context) {
    ItemService items = context.services().require(ItemService.class);
    SimpleLootTableRegistry tables = new SimpleLootTableRegistry();
    DebugLootTables.registerAll(tables);
    DefaultLootService lootService =
        new DefaultLootService(tables, items, new ThreadLocalRandomProvider(), context.eventBus());
    context.services().register(LootTableRegistry.class, tables);
    context.services().register(LootService.class, lootService);
  }

  @Override
  public void start() {
  }

  @Override
  public void stop() {
  }
}
