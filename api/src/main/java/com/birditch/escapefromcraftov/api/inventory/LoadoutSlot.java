package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Set;

/**
 * Named preparation positions carried with an item from LOADOUT into CRAFTOV.
 *
 * <p>Chest rigs and backpacks only accept their explicit container categories;
 * the built-in secure container remains a reserved position.</p>
 */
public enum LoadoutSlot {
  MAIN_HAND(Set.of(ItemCategory.MELEE_WEAPON, ItemCategory.RANGED_WEAPON)),
  OFF_HAND(Set.of(ItemCategory.SHIELD)),
  HEAD(Set.of(ItemCategory.HELMET)),
  BODY_ARMOR(Set.of(ItemCategory.ARMOR)),
  CHEST_RIG(Set.of(ItemCategory.CHEST_RIG)),
  BACKPACK(Set.of(ItemCategory.BACKPACK)),
  QUICK_USE_1(Set.of(ItemCategory.PROJECTILE, ItemCategory.HEALING,
      ItemCategory.FOOD, ItemCategory.POTION, ItemCategory.CONSUMABLE, ItemCategory.KEY)),
  QUICK_USE_2(Set.of(ItemCategory.PROJECTILE, ItemCategory.HEALING,
      ItemCategory.FOOD, ItemCategory.POTION, ItemCategory.CONSUMABLE, ItemCategory.KEY)),
  SECURE_CONTAINER(Set.of());

  private final Set<ItemCategory> acceptedCategories;

  LoadoutSlot(Set<ItemCategory> acceptedCategories) {
    this.acceptedCategories = Set.copyOf(acceptedCategories);
  }

  public boolean accepts(ItemCategory category) {
    return acceptedCategories.contains(Preconditions.checkNotNull(category, "category"));
  }

  public Set<ItemCategory> acceptedCategories() {
    return acceptedCategories;
  }
}
