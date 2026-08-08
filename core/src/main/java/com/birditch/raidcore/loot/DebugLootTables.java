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
    registry.register(new LootTable(LootTableId.of("debug_common"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("copper_wire"), 50, 1, 3),
            new LootEntry(ItemId.of("old_phone"), 30, 1, 1),
            new LootEntry(ItemId.of("medkit"), 15, 1, 1),
            new LootEntry(ItemId.of("hard_drive"), 5, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("debug_military"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("ammo_556"), 50, 10, 30),
            new LootEntry(ItemId.of("medkit"), 25, 1, 2),
            new LootEntry(ItemId.of("hard_drive"), 15, 1, 1),
            new LootEntry(ItemId.of("rare_chip"), 3, 1, 1))))));
  }
}
