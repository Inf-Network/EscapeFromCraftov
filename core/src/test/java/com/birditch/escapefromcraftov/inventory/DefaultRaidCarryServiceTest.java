package com.birditch.escapefromcraftov.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryFailure;
import com.birditch.escapefromcraftov.api.inventory.RaidCarrySnapshot;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultRaidCarryServiceTest {

  private SimpleItemRegistry items;
  private DefaultRaidCarryService service;

  @BeforeEach
  void setUp() {
    items = new SimpleItemRegistry();
    DefaultItems.registerAll(items);
    service = new DefaultRaidCarryService(items);
  }

  @Test
  void namedContainersUnlockPhysicalCargoCells() {
    var capacity = service.capacityFor(List.of(
        loadout("basic_rig", LoadoutSlot.CHEST_RIG),
        loadout("patrol_backpack", LoadoutSlot.BACKPACK)));

    assertEquals(6, items.require(ItemId.of("basic_rig")).containerSlots());
    assertEquals(12, items.require(ItemId.of("patrol_backpack")).containerSlots());
    assertFalse(items.require(ItemId.of("arrow")).secureAllowed());
    assertEquals(4, capacity.pocketSlots());
    assertEquals(18, capacity.containerSlots());
    assertEquals(22, capacity.cargoSlots());
    assertEquals(48_000L, capacity.maximumWeightGrams());
    assertFalse(capacity.legacyCompatibility());
    assertThrows(IllegalArgumentException.class, () -> service.capacityFor(List.of(
        loadout("basic_rig", LoadoutSlot.BACKPACK))));
  }

  @Test
  void historicalNullLoadoutKeepsTheFullLegacyFootprint() {
    UUID id = UUID.randomUUID();
    StoredItem legacy = new StoredItem(id, ItemId.of("iron_sword"), 1,
        InventoryLocation.LOADOUT, null);

    var capacity = service.capacityFor(List.of(legacy));

    assertTrue(capacity.legacyCompatibility());
    assertEquals(27, capacity.containerSlots());
    assertEquals(31, capacity.cargoSlots());
    assertEquals(60_000L, capacity.maximumWeightGrams());
  }

  @Test
  void admissionCountsStacksAsSlotsAndQuantityAsWeight() {
    var capacity = service.capacityFor(List.of());
    RaidCarrySnapshot empty = service.snapshot(capacity, List.of(), List.of());
    RaidCarrySnapshot vanillaSplit = service.snapshot(capacity, List.of(),
        List.of(new ItemInstance(UUID.randomUUID(), ItemId.of("arrow"), 2)), 2);
    assertEquals(2, vanillaSplit.usedCargoSlots());
    assertEquals(60L, vanillaSplit.totalWeightGrams());

    var loaded = service.admit(empty, List.of(
        instance("golden_lion"), instance("golden_lion"), instance("golden_lion")));

    assertTrue(loaded.accepted());
    assertEquals(3, loaded.snapshot().usedCargoSlots());
    assertEquals(9_600L, loaded.snapshot().totalWeightGrams());
    assertTrue(loaded.snapshot().movementMultiplier() < 1.0);

    var tooHeavy = service.admit(empty, List.of(
        instance("golden_lion"), instance("golden_lion"),
        instance("golden_lion"), instance("golden_lion")));
    assertFalse(tooHeavy.accepted());
    assertEquals(RaidCarryFailure.WEIGHT_CAPACITY, tooHeavy.rejection().orElseThrow());
    assertEquals(empty, tooHeavy.snapshot());

    List<ItemInstance> fourPhysicalStacks = new ArrayList<>();
    for (int index = 0; index < 4; index++) fourPhysicalStacks.add(instance("arrow"));
    RaidCarrySnapshot fullPockets = service.snapshot(capacity, List.of(), fourPhysicalStacks);
    var noCell = service.admit(fullPockets, List.of(instance("arrow")));
    assertEquals(RaidCarryFailure.SLOT_CAPACITY, noCell.rejection().orElseThrow());
  }

  private static StoredItem loadout(String itemId, LoadoutSlot slot) {
    UUID identity = UUID.randomUUID();
    return new StoredItem(identity, identity, ItemId.of(itemId), 1,
        InventoryLocation.LOADOUT, null, null, slot);
  }

  private static ItemInstance instance(String itemId) {
    return new ItemInstance(UUID.randomUUID(), ItemId.of(itemId), 1);
  }
}
