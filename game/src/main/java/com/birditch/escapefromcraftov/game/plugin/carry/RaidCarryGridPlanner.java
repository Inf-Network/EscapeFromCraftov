package com.birditch.escapefromcraftov.game.plugin.carry;

import com.birditch.escapefromcraftov.api.inventory.GridItemPlacement;
import com.birditch.escapefromcraftov.api.inventory.GridLayoutMutationResult;
import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridShape;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridLayout;
import com.birditch.escapefromcraftov.api.inventory.ItemGridService;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.inventory.RaidCarryCapacity;
import com.birditch.escapefromcraftov.api.inventory.StoredItem;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Pure bridge between persisted cargo grids and the Minecraft player-inventory cells used to
 * render them. Shapes in this class are server tuning, not official dimensions from another game.
 */
public final class RaidCarryGridPlanner {

  public static final String POCKETS = "pockets";
  public static final String CHEST_RIG = "chest_rig";
  public static final String BACKPACK = "backpack";
  public static final String LEGACY_CONTAINER = "legacy_container";

  private final ItemRegistry items;

  public RaidCarryGridPlanner(ItemRegistry items) {
    this.items = Preconditions.checkNotNull(items, "items");
  }

  public Plan plan(RaidCarryCapacity capacity, List<StoredItem> claimedLoadout) {
    RaidCarryCapacity checkedCapacity = Preconditions.checkNotNull(capacity, "capacity");
    List<StoredItem> claimed = List.copyOf(
        Preconditions.checkNotNull(claimedLoadout, "claimedLoadout"));
    Preconditions.checkArgument(checkedCapacity.pocketSlots() <= 4,
        "the physical player inventory supports at most four tuned pocket cells");

    List<Region> regions = new ArrayList<>();
    if (checkedCapacity.pocketSlots() > 0) {
      regions.add(region(POCKETS, checkedCapacity.pocketSlots(),
          RaidCarryLayout.FIRST_POCKET_SLOT, true));
    }

    int nextContainerSlot = RaidCarryLayout.FIRST_CONTAINER_SLOT;
    if (checkedCapacity.legacyCompatibility()) {
      if (checkedCapacity.containerSlots() > 0) {
        regions.add(region(LEGACY_CONTAINER, checkedCapacity.containerSlots(), nextContainerSlot,
            false));
        nextContainerSlot += checkedCapacity.containerSlots();
      }
    } else {
      int rigSlots = namedContainerSlots(claimed, LoadoutSlot.CHEST_RIG);
      int backpackSlots = namedContainerSlots(claimed, LoadoutSlot.BACKPACK);
      Preconditions.checkArgument(rigSlots + backpackSlots == checkedCapacity.containerSlots(),
          "named rig/backpack cells must equal the authoritative container capacity");
      if (rigSlots > 0) {
        regions.add(region(CHEST_RIG, rigSlots, nextContainerSlot, false));
        nextContainerSlot += rigSlots;
      }
      if (backpackSlots > 0) {
        regions.add(region(BACKPACK, backpackSlots, nextContainerSlot, false));
        nextContainerSlot += backpackSlots;
      }
    }

    Set<Integer> locked = new LinkedHashSet<>();
    for (int slot = RaidCarryLayout.FIRST_POCKET_SLOT + checkedCapacity.pocketSlots();
        slot <= RaidCarryLayout.LAST_POCKET_SLOT; slot++) locked.add(slot);
    for (int slot = nextContainerSlot; slot <= RaidCarryLayout.LAST_CONTAINER_SLOT; slot++) {
      locked.add(slot);
    }
    Map<String, ItemGrid> grids = new LinkedHashMap<>();
    regions.forEach(region -> grids.put(region.id(), ItemGrid.empty(region.shape())));
    return new Plan(regions, new ItemGridLayout(grids), Set.copyOf(locked));
  }

  private int namedContainerSlots(List<StoredItem> claimed, LoadoutSlot slot) {
    return claimed.stream()
        .filter(item -> item.loadoutSlot() == slot)
        .mapToInt(item -> items.require(item.itemId()).containerSlots())
        .sum();
  }

  private static Region region(String id, int cellCount, int firstPhysicalSlot,
      boolean independentCompartments) {
    GridShape shape = tunedShape(cellCount, independentCompartments);
    List<CellSlot> cells = new ArrayList<>(cellCount);
    int physicalSlot = firstPhysicalSlot;
    for (GridShape.Cell cell : shape.cells()) {
      cells.add(new CellSlot(cell.position(), physicalSlot++));
    }
    return new Region(id, shape, cells);
  }

  /** Server-owned compact rectangle/mask tuning; callers may replace it through future config. */
  static GridShape tunedShape(int cellCount) {
    return tunedShape(cellCount, false);
  }

  private static GridShape tunedShape(int cellCount, boolean independentCompartments) {
    Preconditions.checkArgument(cellCount >= 1 && cellCount <= GridShape.MAX_CELLS,
        "cellCount must be between 1 and 54");
    int width = Math.min(GridShape.MAX_WIDTH, (int) Math.ceil(Math.sqrt(cellCount)));
    int height = (cellCount + width - 1) / width;
    while (height > GridShape.MAX_HEIGHT) {
      width++;
      height = (cellCount + width - 1) / width;
    }
    List<GridShape.Cell> cells = new ArrayList<>(cellCount);
    for (int index = 0; index < cellCount; index++) {
      String compartment = independentCompartments ? "pocket_" + index : "main";
      cells.add(new GridShape.Cell(new GridPosition(index % width, index / width), compartment));
    }
    return new GridShape(width, height, cells);
  }

  public record Plan(
      List<Region> regions,
      ItemGridLayout emptyLayout,
      Set<Integer> lockedPhysicalSlots) {

    public Plan {
      regions = List.copyOf(Preconditions.checkNotNull(regions, "regions"));
      Preconditions.checkNotNull(emptyLayout, "emptyLayout");
      lockedPhysicalSlots = Set.copyOf(
          Preconditions.checkNotNull(lockedPhysicalSlots, "lockedPhysicalSlots"));
    }

    public Optional<PlannedPlacement> autoPlace(
        ItemGridService grids, ItemGridLayout layout, ItemInstance item) {
      Preconditions.checkNotNull(grids, "grids");
      ItemGridLayout checkedLayout = Preconditions.checkNotNull(layout, "layout");
      Preconditions.checkNotNull(item, "item");
      for (Region region : regions) {
        GridLayoutMutationResult result = grids.autoPlace(checkedLayout, region.id(), item);
        if (!result.accepted()) continue;
        return Optional.of(new PlannedPlacement(result.layout(), region.id(), result.placement()));
      }
      return Optional.empty();
    }

    /** Deterministically repacks every source anchor or rejects without a partial target. */
    public Optional<ItemGridLayout> repack(ItemGridService grids, ItemGridLayout source) {
      Preconditions.checkNotNull(grids, "grids");
      Preconditions.checkNotNull(source, "source");
      ItemGridLayout result = emptyLayout;
      for (ItemGrid grid : source.grids().values()) {
        for (GridItemPlacement placement : grid.placements()) {
          PlannedPlacement placed = autoPlace(grids, result, placement.item()).orElse(null);
          if (placed == null) return Optional.empty();
          result = placed.layout();
        }
      }
      return Optional.of(result);
    }

    public Optional<Region> region(String gridId) {
      Preconditions.checkNotNull(gridId, "gridId");
      return regions.stream().filter(region -> region.id().equals(gridId)).findFirst();
    }

    public Optional<Region> regionForPhysicalSlot(int slot) {
      return regions.stream().filter(region -> region.containsPhysicalSlot(slot)).findFirst();
    }

    public Set<Integer> cargoPhysicalSlots() {
      Set<Integer> result = new LinkedHashSet<>();
      regions.forEach(region -> region.cells().forEach(cell -> result.add(cell.physicalSlot())));
      return Set.copyOf(result);
    }
  }

  public record Region(String id, GridShape shape, List<CellSlot> cells) {

    public Region {
      Preconditions.checkNotNull(id, "id");
      Preconditions.checkArgument(!id.isBlank(), "id must not be blank");
      Preconditions.checkNotNull(shape, "shape");
      cells = List.copyOf(Preconditions.checkNotNull(cells, "cells"));
      Preconditions.checkArgument(cells.size() == shape.allowedCellCount(),
          "every allowed grid cell requires exactly one physical slot");
    }

    public Optional<Integer> physicalSlot(GridPosition position) {
      Preconditions.checkNotNull(position, "position");
      return cells.stream().filter(cell -> cell.position().equals(position))
          .map(CellSlot::physicalSlot).findFirst();
    }

    public Optional<GridPosition> positionForPhysicalSlot(int slot) {
      return cells.stream().filter(cell -> cell.physicalSlot() == slot)
          .map(CellSlot::position).findFirst();
    }

    public boolean containsPhysicalSlot(int slot) {
      return cells.stream().anyMatch(cell -> cell.physicalSlot() == slot);
    }
  }

  public record CellSlot(GridPosition position, int physicalSlot) {

    public CellSlot {
      Preconditions.checkNotNull(position, "position");
      Preconditions.checkArgument(physicalSlot >= 0 && physicalSlot <= 35,
          "physical slot must be inside player storage");
    }
  }

  public record PlannedPlacement(
      ItemGridLayout layout,
      String gridId,
      GridItemPlacement placement) {

    public PlannedPlacement {
      Preconditions.checkNotNull(layout, "layout");
      Preconditions.checkNotNull(gridId, "gridId");
      Preconditions.checkNotNull(placement, "placement");
    }
  }
}
