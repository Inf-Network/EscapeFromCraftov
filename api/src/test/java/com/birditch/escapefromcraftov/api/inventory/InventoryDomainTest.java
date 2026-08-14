package com.birditch.escapefromcraftov.api.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryDomainTest {
  private static final UUID PLAYER = UUID.randomUUID();
  private static final UUID STORAGE = UUID.randomUUID();

  @Test
  void craftovRowsRequireCraftovId() {
    assertThrows(IllegalArgumentException.class, () -> new StoredItem(STORAGE,
        ItemId.of("emerald"), 1, InventoryLocation.CRAFTOV, null));
  }

  @Test
  void snapshotFiltersByLocationWithoutExposingMutableList() {
    PlayerInventorySnapshot snapshot = new PlayerInventorySnapshot(PLAYER, List.of(
        new StoredItem(STORAGE, ItemId.of("emerald"), 2, InventoryLocation.WAREHOUSE, null)));

    assertEquals(1, snapshot.at(InventoryLocation.WAREHOUSE).size());
    assertEquals(0, snapshot.at(InventoryLocation.LOADOUT).size());
    assertThrows(UnsupportedOperationException.class, () -> snapshot.items().clear());
  }

  @Test
  void craftovIdIsAvailableOnlyForCraftovRows() {
    StoredItem row = new StoredItem(STORAGE, ItemId.of("emerald"), 1,
        InventoryLocation.CRAFTOV, CraftovGameId.of("craftov-1"));
    assertEquals("craftov-1", row.currentCraftov().orElseThrow().value());
  }

  @Test
  void legacyStoredItemUsesStorageIdAsStableInstanceFallback() {
    StoredItem row = new StoredItem(STORAGE, ItemId.of("emerald"), 1,
        InventoryLocation.WAREHOUSE, null);

    assertEquals(STORAGE, row.instanceId());
    assertEquals(STORAGE, row.storageId());
    assertTrue(row.payload().isEmpty());
  }

  @Test
  void namedLoadoutSlotsAreLimitedToLoadoutLifecycleAndCompatibleCategories() {
    StoredItem equipped = new StoredItem(STORAGE, STORAGE, ItemId.of("iron_sword"), 1,
        InventoryLocation.LOADOUT, null, null, LoadoutSlot.MAIN_HAND);

    assertEquals(LoadoutSlot.MAIN_HAND, equipped.namedLoadoutSlot().orElseThrow());
    assertTrue(LoadoutSlot.MAIN_HAND.accepts(ItemCategory.MELEE_WEAPON));
    assertTrue(LoadoutSlot.QUICK_USE_1.accepts(ItemCategory.POTION));
    assertFalse(LoadoutSlot.CHEST_RIG.accepts(ItemCategory.MISC));
    assertFalse(LoadoutSlot.BACKPACK.accepts(ItemCategory.BACKPACK));
    assertFalse(LoadoutSlot.SECURE_CONTAINER.accepts(ItemCategory.VALUABLE));
    assertThrows(IllegalArgumentException.class, () -> new StoredItem(
        STORAGE, STORAGE, ItemId.of("iron_sword"), 1,
        InventoryLocation.WAREHOUSE, null, null, LoadoutSlot.MAIN_HAND));
  }
}
