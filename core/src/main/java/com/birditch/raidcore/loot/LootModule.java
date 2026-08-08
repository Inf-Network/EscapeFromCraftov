package com.birditch.raidcore.loot;

import com.birditch.raidcore.api.item.ItemService;
import com.birditch.raidcore.api.loot.LootService;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.common.random.ThreadLocalRandomProvider;
import com.birditch.raidcore.core.RaidContext;
import com.birditch.raidcore.core.module.RaidModule;

/// Wires the loot domain: table registry, roll service and the built-in debug
/// tables. Requires the item module to be initialized first, because
/// [ItemService] is resolved from the service registry. All wiring happens in
/// `initialize`; `start` and `stop` have no behavior.
public final class LootModule implements RaidModule {

  public LootModule() {
  }

  @Override
  public String id() {
    return "loot";
  }

  @Override
  public void initialize(RaidContext context) {
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
