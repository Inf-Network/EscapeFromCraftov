package com.birditch.raidcore.loot;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.event.loot.LootGeneratedEvent;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.item.ItemService;
import com.birditch.raidcore.api.loot.LootEntry;
import com.birditch.raidcore.api.loot.LootPool;
import com.birditch.raidcore.api.loot.LootRollContext;
import com.birditch.raidcore.api.loot.LootService;
import com.birditch.raidcore.api.loot.LootTable;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.random.RandomProvider;
import com.birditch.raidcore.common.random.WeightedRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// Default [LootService] rolling registered tables into concrete item stacks.
///
/// One [WeightedRandom] selector is built lazily per pool and cached in a plain
/// HashMap: all rolls are main-thread confined and the table registry is
/// append-only, so cached selectors never become stale.
public final class DefaultLootService implements LootService {

  private final LootTableRegistry tables;
  private final ItemService items;
  private final RandomProvider random;
  private final EventBus eventBus;
  private final Map<LootPool, WeightedRandom<LootEntry>> selectors = new HashMap<>();

  public DefaultLootService(
      LootTableRegistry tables, ItemService items, RandomProvider random, EventBus eventBus) {
    this.tables = Preconditions.checkNotNull(tables, "tables");
    this.items = Preconditions.checkNotNull(items, "items");
    this.random = Preconditions.checkNotNull(random, "random");
    this.eventBus = Preconditions.checkNotNull(eventBus, "eventBus");
  }

  @Override
  public List<ItemInstance> roll(LootTableId tableId, LootRollContext context) {
    Preconditions.checkNotNull(tableId, "tableId");
    Preconditions.checkNotNull(context, "context");
    LootTable table = tables.require(tableId);
    List<ItemInstance> rolled = new ArrayList<>();
    for (LootPool pool : table.pools()) {
      WeightedRandom<LootEntry> selector =
          selectors.computeIfAbsent(pool, DefaultLootService::buildSelector);
      for (int i = 0; i < pool.rolls(); i++) {
        LootEntry entry = selector.pick(random);
        int quantity = random.between(entry.minQuantity(), entry.maxQuantity());
        rolled.add(items.createInstance(entry.itemId(), quantity));
      }
    }
    List<ItemInstance> result = List.copyOf(rolled);
    eventBus.publish(new LootGeneratedEvent(tableId, result));
    return result;
  }

  private static WeightedRandom<LootEntry> buildSelector(LootPool pool) {
    WeightedRandom.Builder<LootEntry> builder = WeightedRandom.builder();
    for (LootEntry entry : pool.entries()) {
      builder.add(entry, entry.weight());
    }
    return builder.build();
  }
}
