package com.birditch.escapefromcraftov.loot;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.loot.LootEntry;
import com.birditch.escapefromcraftov.api.loot.LootPool;
import com.birditch.escapefromcraftov.api.loot.LootTable;
import com.birditch.escapefromcraftov.api.loot.LootTableId;
import com.birditch.escapefromcraftov.api.loot.LootTableRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// Built-in loot tables referencing the demo items from
/// [com.birditch.escapefromcraftov.item.DefaultItems]; intended for development and
/// `/craftov debug loot` verification.
public final class DebugLootTables {

  private DebugLootTables() {
  }

  public static void registerAll(LootTableRegistry registry) {
    Preconditions.checkNotNull(registry, "registry");
    registry.register(new LootTable(LootTableId.of("craftov_tier_1"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("emerald"), 48, 1, 4),
            new LootEntry(ItemId.of("arrow"), 35, 4, 12),
            new LootEntry(ItemId.of("healing_potion"), 12, 1, 1),
            new LootEntry(ItemId.of("medical_sample"), 5, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("craftov_tier_2"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("emerald"), 28, 2, 6),
            new LootEntry(ItemId.of("medical_sample"), 18, 1, 2),
            new LootEntry(ItemId.of("iron_sword"), 18, 1, 1),
            new LootEntry(ItemId.of("bow"), 15, 1, 1),
            new LootEntry(ItemId.of("shield"), 12, 1, 1),
            new LootEntry(ItemId.of("healing_potion"), 15, 1, 2),
            new LootEntry(ItemId.of("encrypted_drive"), 4, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("craftov_tier_3"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("crossbow"), 15, 1, 1),
            new LootEntry(ItemId.of("iron_chestplate"), 14, 1, 1),
            new LootEntry(ItemId.of("golden_apple"), 14, 1, 2),
            new LootEntry(ItemId.of("echo_shard"), 25, 1, 3),
            new LootEntry(ItemId.of("encrypted_drive"), 18, 1, 2),
            new LootEntry(ItemId.of("military_processor"), 5, 1, 1),
            new LootEntry(ItemId.of("extraction_key"), 9, 1, 1))))));
    registry.register(new LootTable(LootTableId.of("craftov_tier_4"), List.of(
        new LootPool(3, List.of(
            new LootEntry(ItemId.of("ancient_debris"), 18, 1, 2),
            new LootEntry(ItemId.of("echo_shard"), 22, 2, 5),
            new LootEntry(ItemId.of("encrypted_drive"), 20, 1, 3),
            new LootEntry(ItemId.of("military_processor"), 17, 1, 2),
            new LootEntry(ItemId.of("golden_lion"), 8, 1, 1),
            new LootEntry(ItemId.of("classified_document"), 2, 1, 1),
            new LootEntry(ItemId.of("golden_apple"), 8, 1, 3),
            new LootEntry(ItemId.of("extraction_key"), 5, 1, 1))))));
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
