package com.birditch.escapefromcraftov.game.plugin.carry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.inventory.DeterministicItemGridService;
import com.birditch.escapefromcraftov.item.DefaultItems;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidCarryGridPlannerTest {

  @Test
  void separatesPocketRigAndBackpackAndNeverLetsLargeItemsCrossPockets() {
    SimpleItemRegistry items = new SimpleItemRegistry();
    DefaultItems.registerAll(items);
    RaidCarryGridPlanner planner = new RaidCarryGridPlanner(items);
    var plan = planner.plan(new RaidCarryCapacity(4, 18, 20_000, 40_000, false),
        List.of(stored("basic_rig", LoadoutSlot.CHEST_RIG),
            stored("patrol_backpack", LoadoutSlot.BACKPACK)));

    assertEquals(List.of(RaidCarryGridPlanner.POCKETS, RaidCarryGridPlanner.CHEST_RIG,
            RaidCarryGridPlanner.BACKPACK),
        plan.regions().stream().map(RaidCarryGridPlanner.Region::id).toList());
    assertEquals(4, plan.region(RaidCarryGridPlanner.POCKETS).orElseThrow()
        .shape().cells().stream().map(cell -> cell.compartment()).distinct().count());
    assertEquals(6, plan.region(RaidCarryGridPlanner.CHEST_RIG).orElseThrow().cells().size());
    assertEquals(12, plan.region(RaidCarryGridPlanner.BACKPACK).orElseThrow().cells().size());
    assertEquals(9, plan.lockedPhysicalSlots().size());

    var grids = new DeterministicItemGridService(items);
    ItemInstance large = new ItemInstance(UUID.randomUUID(), ItemId.of("basic_rig"), 1);
    assertFalse(grids.autoPlace(plan.emptyLayout(), RaidCarryGridPlanner.POCKETS, large).accepted());
    ItemInstance small = new ItemInstance(UUID.randomUUID(), ItemId.of("emerald"), 1);
    var placed = plan.autoPlace(grids, plan.emptyLayout(), small).orElseThrow();
    assertEquals(RaidCarryGridPlanner.POCKETS, placed.gridId());
    assertTrue(placed.layout().find(RaidCarryGridPlanner.POCKETS).orElseThrow()
        .find(small.instanceId()).isPresent());
  }

  @Test
  void legacyKeepsItsOwnTwentySevenCellCompatibilityGrid() {
    SimpleItemRegistry items = new SimpleItemRegistry();
    DefaultItems.registerAll(items);
    var plan = new RaidCarryGridPlanner(items).plan(
        new RaidCarryCapacity(4, 27, 30_000, 60_000, true), List.of());

    assertEquals(List.of(RaidCarryGridPlanner.POCKETS, RaidCarryGridPlanner.LEGACY_CONTAINER),
        plan.regions().stream().map(RaidCarryGridPlanner.Region::id).toList());
    assertEquals(27, plan.region(RaidCarryGridPlanner.LEGACY_CONTAINER)
        .orElseThrow().cells().size());
    assertTrue(plan.lockedPhysicalSlots().isEmpty());
  }

  @Test
  void backpackRemovalRepackIsAllOrNothing() {
    SimpleItemRegistry items = new SimpleItemRegistry();
    DefaultItems.registerAll(items);
    var grids = new DeterministicItemGridService(items);
    RaidCarryGridPlanner planner = new RaidCarryGridPlanner(items);
    var full = planner.plan(new RaidCarryCapacity(4, 18, 20_000, 40_000, false),
        List.of(stored("basic_rig", LoadoutSlot.CHEST_RIG),
            stored("patrol_backpack", LoadoutSlot.BACKPACK)));
    var rigOnly = planner.plan(new RaidCarryCapacity(4, 6, 12_000, 24_000, false),
        List.of(stored("basic_rig", LoadoutSlot.CHEST_RIG)));

    ItemInstance tall = new ItemInstance(UUID.randomUUID(), ItemId.of("iron_sword"), 1);
    var inBackpack = full.autoPlace(grids, full.emptyLayout(), tall).orElseThrow();
    assertEquals(RaidCarryGridPlanner.BACKPACK, inBackpack.gridId());
    assertTrue(rigOnly.repack(grids, inBackpack.layout()).isEmpty(),
        "a failed shrink must not return a partial target layout");

    ItemInstance small = new ItemInstance(UUID.randomUUID(), ItemId.of("emerald"), 1);
    var pocket = full.autoPlace(grids, full.emptyLayout(), small).orElseThrow();
    assertTrue(rigOnly.repack(grids, pocket.layout()).isPresent());
  }

  private static StoredItem stored(String itemId, LoadoutSlot slot) {
    return new StoredItem(UUID.randomUUID(), UUID.randomUUID(), ItemId.of(itemId), 1,
        InventoryLocation.LOADOUT, null, null, slot);
  }
}
