package com.birditch.raidcore.loot;

import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.loot.LootEntry;
import com.birditch.raidcore.api.loot.LootPool;
import com.birditch.raidcore.api.loot.LootTable;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// Built-in loot tables referencing the demo items from
/// [com.birditch.raidcore.item.DefaultItems]; intended for development and
/// `/raid debug loot` verification.
public final class DebugLootTables {

  private DebugLootTables() {
  }

  public static void registerAll(LootTableRegistry registry) {
    Preconditions.checkNotNull(registry, "registry");
    registry.register(new LootTable(LootTableId.of("raid_tier_1"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("emerald"), 50, 1, 4),
            new LootEntry(ItemId.of("arrow"), 35, 4, 12),
            new LootEntry(ItemId.of("healing_potion"), 15, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("raid_tier_2"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("emerald"), 35, 2, 6),
            new LootEntry(ItemId.of("iron_sword"), 18, 1, 1),
            new LootEntry(ItemId.of("bow"), 15, 1, 1),
            new LootEntry(ItemId.of("shield"), 12, 1, 1),
            new LootEntry(ItemId.of("healing_potion"), 20, 1, 2))))));
    registry.register(new LootTable(LootTableId.of("raid_tier_3"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("crossbow"), 20, 1, 1),
            new LootEntry(ItemId.of("iron_chestplate"), 18, 1, 1),
            new LootEntry(ItemId.of("golden_apple"), 15, 1, 2),
            new LootEntry(ItemId.of("echo_shard"), 32, 1, 3),
            new LootEntry(ItemId.of("extraction_key"), 15, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("raid_tier_4"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("ancient_debris"), 10, 1, 2),
            new LootEntry(ItemId.of("echo_shard"), 35, 2, 5),
            new LootEntry(ItemId.of("golden_apple"), 25, 1, 3),
            new LootEntry(ItemId.of("extraction_key"), 30, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("debug_common"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("emerald"), 50, 1, 4),
            new LootEntry(ItemId.of("arrow"), 30, 4, 12),
            new LootEntry(ItemId.of("healing_potion"), 15, 1, 1),
            new LootEntry(ItemId.of("echo_shard"), 5, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("debug_armory"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("arrow"), 50, 8, 24),
            new LootEntry(ItemId.of("iron_sword"), 25, 1, 1),
            new LootEntry(ItemId.of("bow"), 15, 1, 1),
            new LootEntry(ItemId.of("crossbow"), 8, 1, 1),
            new LootEntry(ItemId.of("shield"), 6, 1, 1),
            new LootEntry(ItemId.of("iron_chestplate"), 4, 1, 1),
            new LootEntry(ItemId.of("golden_apple"), 3, 1, 1),
            new LootEntry(ItemId.of("ancient_debris"), 1, 1, 1))))));
  }
}
