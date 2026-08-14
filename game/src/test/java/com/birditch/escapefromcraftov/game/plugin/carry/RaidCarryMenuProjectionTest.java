package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import com.birditch.escapefromcraftov.inventory.DeterministicItemGridService;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidCarryMenuProjectionTest {

  @Test
  void mapsEveryServerRegionCellOnceAndShowsTheExactLockedCount() {
    SimpleItemRegistry items = new SimpleItemRegistry();
    DefaultItems.registerAll(items);
    var plan = new RaidCarryGridPlanner(items).plan(
        new RaidCarryCapacity(4, 18, 20_000, 40_000, false),
        List.of(stored("basic_rig", LoadoutSlot.CHEST_RIG),
            stored("patrol_backpack", LoadoutSlot.BACKPACK)));

    var projection = RaidCarryMenuProjection.project(
        plan.regions(), plan.lockedPhysicalSlots().size());

    assertEquals(22, projection.cells().stream()
        .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.REGION).count());
    assertEquals(9, projection.cells().stream()
        .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.LOCKED).count());
    assertEquals(RaidCarryMenuProjection.Kind.SUMMARY,
        projection.cell(RaidCarryMenuProjection.SUMMARY_SLOT).kind());
    assertEquals(RaidCarryMenuProjection.Kind.CLOSE,
        projection.cell(RaidCarryMenuProjection.CLOSE_SLOT).kind());
    for (var region : plan.regions()) {
      for (var shapeCell : region.shape().cells()) {
        assertEquals(1, projection.cells().stream().filter(cell ->
            cell.kind() == RaidCarryMenuProjection.Kind.REGION
                && region.id().equals(cell.gridId())
                && shapeCell.position().equals(cell.position())).count());
      }
    }
  }

  @Test
  void sameGridTransferCanSlideAnAnchorOntoItsOwnCoveredCell() {
    ItemId itemId = ItemId.of("slide_test");
    SimpleItemRegistry items = new SimpleItemRegistry();
    items.register(ItemDefinition.builder(itemId, "Slide Test", ItemCategory.MATERIAL,
            ItemRarity.COMMON).footprint(ItemFootprint.of(2, 1)).build());
    var grids = new DeterministicItemGridService(items);
    var plan = new RaidCarryGridPlanner(items).plan(
        new RaidCarryCapacity(4, 6, 5_000, 10_000, true), List.of());
    ItemInstance item = new ItemInstance(UUID.randomUUID(), itemId, 1);
    var placed = grids.place(plan.emptyLayout(), RaidCarryGridPlanner.LEGACY_CONTAINER,
        item, new GridPosition(0, 0), GridRotation.DEFAULT);
    assertTrue(placed.accepted());

    var shifted = grids.transfer(placed.layout(), RaidCarryGridPlanner.LEGACY_CONTAINER,
        RaidCarryGridPlanner.LEGACY_CONTAINER, item.instanceId(),
        new GridPosition(1, 0), GridRotation.DEFAULT);

    assertTrue(shifted.accepted());
    assertEquals(new GridPosition(1, 0), shifted.placement().anchor());
    assertEquals(new GridPosition(0, 0), placed.placement().anchor(),
        "the immutable pre-move layout must remain unchanged");
  }

  private static StoredItem stored(String itemId, LoadoutSlot slot) {
    return new StoredItem(UUID.randomUUID(), UUID.randomUUID(), ItemId.of(itemId), 1,
        InventoryLocation.LOADOUT, null, null, slot);
  }
}
