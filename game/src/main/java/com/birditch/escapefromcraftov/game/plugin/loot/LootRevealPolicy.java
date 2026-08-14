package com.birditch.escapefromcraftov.game.plugin.loot;

import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;

/** Pure timing policy for the slot-by-slot loot identification animation. */
final class LootRevealPolicy {

  private LootRevealPolicy() {}

  static long delayMillis(LootContainerType type, int tier, ItemRarity rarity) {
    long containerDelay = switch (type) {
      case BARREL -> 320;
      case CHEST -> 400;
      case DUNGEON_CHEST -> 470;
      case POTION_CACHE -> 500;
      case HIDDEN_CACHE -> 540;
      case SUPPLY_DROP -> 620;
      case VAULT -> 700;
    };
    long rarityDelay = switch (rarity) {
      case COMMON -> 0;
      case UNCOMMON -> 100;
      case RARE -> 240;
      case EPIC -> 460;
      case LEGENDARY -> 760;
      case ARTIFACT -> 1_100;
    };
    return containerDelay + Math.max(0, tier - 1) * 70L + rarityDelay;
  }
}
