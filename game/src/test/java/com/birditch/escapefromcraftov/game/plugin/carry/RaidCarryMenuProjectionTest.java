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
import java.util.ArrayList;
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
    assertEquals(3, projection.cells().stream()
        .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.HEADER).count());
    assertEquals(6, projection.cells().stream()
        .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.BORDER).count());
    assertTrue(projection.equipmentStrip());
    assertEquals(6, projection.cells().stream()
        .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.EQUIPMENT).count());
    assertEquals(RaidCarryLayout.MAIN_HAND_SLOT,
        projection.cell(RaidCarryMenuProjection.FIRST_EQUIPMENT_SLOT).equipmentSlot());
    assertEquals(RaidCarryLayout.BACKPACK_SLOT,
        projection.cell(RaidCarryMenuProjection.LAST_EQUIPMENT_SLOT).equipmentSlot());
    assertEquals(RaidCarryMenuProjection.Kind.SECURE,
        projection.cell(RaidCarryMenuProjection.SECURE_SLOT).kind());
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
      assertEquals(1, projection.cells().stream().filter(cell ->
          cell.kind() == RaidCarryMenuProjection.Kind.HEADER
              && region.id().equals(cell.gridId())).count());
    }
  }

  @Test
  void everyLegalRegionSplitStillFitsWithHeadersAndThreeFixedControls() {
    for (int pockets = 0; pockets <= 4; pockets++) {
      for (int rig = 0; rig <= 27; rig++) {
        for (int backpack = 0; backpack <= 27 - rig; backpack++) {
          List<RaidCarryGridPlanner.Region> regions = new ArrayList<>();
          int nextSlot = 3;
          if (pockets > 0) {
            regions.add(region(RaidCarryGridPlanner.POCKETS, pockets, nextSlot));
            nextSlot += pockets;
          }
          if (rig > 0) {
            regions.add(region(RaidCarryGridPlanner.CHEST_RIG, rig, nextSlot));
            nextSlot += rig;
          }
          if (backpack > 0) {
            regions.add(region(RaidCarryGridPlanner.BACKPACK, backpack, nextSlot));
          }
          int locked = 31 - pockets - rig - backpack;
          RaidCarryMenuProjection.Projection projection;
          try {
            projection = RaidCarryMenuProjection.project(regions, locked);
          } catch (RuntimeException failure) {
            throw new AssertionError("pockets=" + pockets + ", rig=" + rig
                + ", backpack=" + backpack, failure);
          }
          assertEquals(RaidCarryMenuProjection.Kind.SECURE,
              projection.cell(RaidCarryMenuProjection.SECURE_SLOT).kind());
          assertEquals(RaidCarryMenuProjection.Kind.SUMMARY,
              projection.cell(RaidCarryMenuProjection.SUMMARY_SLOT).kind());
          assertEquals(RaidCarryMenuProjection.Kind.CLOSE,
              projection.cell(RaidCarryMenuProjection.CLOSE_SLOT).kind());
          assertTrue(projection.equipmentStrip());
          assertEquals(6, projection.cells().stream()
              .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.EQUIPMENT).count());
          assertEquals(pockets + rig + backpack, projection.cells().stream()
              .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.REGION).count());
          assertEquals(regions.size(), projection.cells().stream()
              .filter(cell -> cell.kind() == RaidCarryMenuProjection.Kind.HEADER).count());
          regions.forEach(region -> assertRelativeGeometryPreserved(projection, region));
        }
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

  private static RaidCarryGridPlanner.Region region(String id, int cells, int firstSlot) {
    var shape = RaidCarryGridPlanner.tunedShape(cells);
    List<RaidCarryGridPlanner.CellSlot> slots = new ArrayList<>();
    int physicalSlot = firstSlot;
    for (var cell : shape.cells()) {
      slots.add(new RaidCarryGridPlanner.CellSlot(cell.position(), physicalSlot++));
    }
    return new RaidCarryGridPlanner.Region(id, shape, slots);
  }

  private static void assertRelativeGeometryPreserved(
      RaidCarryMenuProjection.Projection projection, RaidCarryGridPlanner.Region region) {
    Integer originColumn = null;
    Integer originRow = null;
    for (int slot = 0; slot < projection.cells().size(); slot++) {
      var cell = projection.cell(slot);
      if (cell.kind() != RaidCarryMenuProjection.Kind.REGION
          || !region.id().equals(cell.gridId())) continue;
      int candidateColumn = slot % 9 - cell.position().column();
      int candidateRow = slot / 9 - cell.position().row();
      if (originColumn == null) {
        originColumn = candidateColumn;
        originRow = candidateRow;
      } else {
        assertEquals(originColumn, candidateColumn,
            "display projection must preserve horizontal grid adjacency for " + region.id());
        assertEquals(originRow, candidateRow,
            "display projection must preserve vertical grid adjacency for " + region.id());
      }
    }
  }
}
