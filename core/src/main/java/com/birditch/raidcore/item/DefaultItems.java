package com.birditch.raidcore.item;

import com.birditch.raidcore.api.economy.Money;
import com.birditch.raidcore.api.item.ItemCategory;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemRarity;
import com.birditch.raidcore.api.item.ItemRegistry;
import com.birditch.raidcore.common.check.Preconditions;

/// Demo item catalog registered during bootstrap.
public final class DefaultItems {

  private DefaultItems() {
  }

  public static void registerAll(ItemRegistry registry) {
    Preconditions.checkNotNull(registry, "registry");
    registry.register(ItemDefinition
        .builder(ItemId.of("old_phone"), "Old Phone", ItemCategory.VALUABLE, ItemRarity.COMMON)
        .basePrice(Money.of(1500L))
        .weightGrams(200)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("copper_wire"), "Copper Wire", ItemCategory.MATERIAL, ItemRarity.COMMON)
        .basePrice(Money.of(800L))
        .weightGrams(100)
        .stackSize(16)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("hard_drive"), "Hard Drive", ItemCategory.VALUABLE, ItemRarity.RARE)
        .basePrice(Money.of(6000L))
        .weightGrams(400)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("rare_chip"), "Rare Chip", ItemCategory.VALUABLE, ItemRarity.EPIC)
        .basePrice(Money.of(35000L))
        .weightGrams(50)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("medkit"), "Medkit", ItemCategory.MEDICAL, ItemRarity.UNCOMMON)
        .basePrice(Money.of(2000L))
        .weightGrams(800)
        .stackSize(4)
        .build());
    registry.register(ItemDefinition
        .builder(ItemId.of("ammo_556"), "5.56mm Ammo", ItemCategory.AMMO, ItemRarity.COMMON)
        .basePrice(Money.of(120L))
        .weightGrams(12)
        .stackSize(64)
        .build());
  }
}
