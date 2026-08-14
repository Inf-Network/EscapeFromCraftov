package com.birditch.escapefromcraftov.inventory;

import com.birditch.escapefromcraftov.api.inventory.GridItemPlacement;
import com.birditch.escapefromcraftov.api.inventory.GridLayoutMutationResult;
import com.birditch.escapefromcraftov.api.inventory.GridPlacementFailure;
import com.birditch.escapefromcraftov.api.inventory.GridPlacementResult;
import com.birditch.escapefromcraftov.api.inventory.GridPosition;
import com.birditch.escapefromcraftov.api.inventory.GridRotation;
import com.birditch.escapefromcraftov.api.inventory.ItemGrid;
import com.birditch.escapefromcraftov.api.inventory.ItemGridLayout;
import com.birditch.escapefromcraftov.api.inventory.ItemGridService;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Registry-backed, deterministic implementation of anchor-only rectangular item grids. */
public final class DeterministicItemGridService implements ItemGridService {

  private final ItemRegistry items;

  public DeterministicItemGridService(ItemRegistry items) {
    this.items = Preconditions.checkNotNull(items, "items");
  }

  @Override
  public Set<GridPosition> occupiedCells(ItemGrid grid) {
    ValidatedGrid validated = requireValid(grid);
    return Collections.unmodifiableSet(new LinkedHashSet<>(validated.byCell().keySet()));
  }

  @Override
  public Optional<GridItemPlacement> placementAt(ItemGrid grid, GridPosition cell) {
    Preconditions.checkNotNull(cell, "cell");
    return Optional.ofNullable(requireValid(grid).byCell().get(cell));
  }

  @Override
  public GridPlacementResult place(ItemGrid grid, ItemInstance item, GridPosition anchor,
      GridRotation rotation) {
    ItemGrid checkedGrid = Preconditions.checkNotNull(grid, "grid");
    Preconditions.checkNotNull(item, "item");
    Preconditions.checkNotNull(anchor, "anchor");
    Preconditions.checkNotNull(rotation, "rotation");
    ValidatedGrid validated = validateOrNull(checkedGrid);
    if (validated == null) return rejected(checkedGrid, GridPlacementFailure.INVALID_GRID);
    if (validated.byIdentity().containsKey(item.instanceId())) {
      return rejected(checkedGrid, GridPlacementFailure.DUPLICATE_INSTANCE);
    }
    Candidate candidate = candidate(checkedGrid, item, anchor, rotation,
        registeredFootprint(item));
    if (candidate.failure() != null) return rejected(checkedGrid, candidate.failure());
    if (!Collections.disjoint(validated.byCell().keySet(), candidate.cells())) {
      return rejected(checkedGrid, GridPlacementFailure.OCCUPIED);
    }
    List<GridItemPlacement> placements = new ArrayList<>(checkedGrid.placements());
    placements.add(candidate.placement());
    return GridPlacementResult.accepted(
        new ItemGrid(checkedGrid.width(), checkedGrid.height(), placements,
            checkedGrid.shape()),
        candidate.placement());
  }

  @Override
  public GridPlacementResult autoPlace(ItemGrid grid, ItemInstance item) {
    ItemGrid checkedGrid = Preconditions.checkNotNull(grid, "grid");
    Preconditions.checkNotNull(item, "item");
    ValidatedGrid validated = validateOrNull(checkedGrid);
    if (validated == null) return rejected(checkedGrid, GridPlacementFailure.INVALID_GRID);
    if (validated.byIdentity().containsKey(item.instanceId())) {
      return rejected(checkedGrid, GridPlacementFailure.DUPLICATE_INSTANCE);
    }
    ItemDefinition definition = items.find(item.itemId()).orElse(null);
    if (definition == null) return rejected(checkedGrid, GridPlacementFailure.UNKNOWN_ITEM);
    if (item.quantity() > definition.stackSize()) {
      return rejected(checkedGrid, GridPlacementFailure.INVALID_PHYSICAL_STACK);
    }

    List<GridRotation> rotations = definition.footprint().rotatable()
        && definition.footprint().width() != definition.footprint().height()
        ? List.of(GridRotation.DEFAULT, GridRotation.CLOCKWISE_90)
        : List.of(GridRotation.DEFAULT);
    for (int row = 0; row < checkedGrid.height(); row++) {
      for (int column = 0; column < checkedGrid.width(); column++) {
        GridPosition anchor = new GridPosition(column, row);
        for (GridRotation rotation : rotations) {
          Candidate candidate = candidate(checkedGrid, item, anchor, rotation,
              definition.footprint());
          if (candidate.failure() == null
              && Collections.disjoint(validated.byCell().keySet(), candidate.cells())) {
            List<GridItemPlacement> placements = new ArrayList<>(checkedGrid.placements());
            placements.add(candidate.placement());
            return GridPlacementResult.accepted(
                new ItemGrid(checkedGrid.width(), checkedGrid.height(), placements,
                    checkedGrid.shape()),
                candidate.placement());
          }
        }
      }
    }
    return rejected(checkedGrid, GridPlacementFailure.NO_SPACE);
  }

  @Override
  public GridPlacementResult move(ItemGrid grid, UUID instanceId, GridPosition anchor,
      GridRotation rotation) {
    ItemGrid checkedGrid = Preconditions.checkNotNull(grid, "grid");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(anchor, "anchor");
    Preconditions.checkNotNull(rotation, "rotation");
    ValidatedGrid validated = validateOrNull(checkedGrid);
    if (validated == null) return rejected(checkedGrid, GridPlacementFailure.INVALID_GRID);
    GridItemPlacement existing = validated.byIdentity().get(instanceId);
    if (existing == null) return rejected(checkedGrid, GridPlacementFailure.ITEM_NOT_FOUND);

    Candidate candidate = candidate(checkedGrid, existing.item(), anchor, rotation,
        existing.footprint());
    if (candidate.failure() != null) return rejected(checkedGrid, candidate.failure());
    for (GridPosition cell : candidate.cells()) {
      GridItemPlacement occupant = validated.byCell().get(cell);
      if (occupant != null && !occupant.item().instanceId().equals(instanceId)) {
        return rejected(checkedGrid, GridPlacementFailure.OCCUPIED);
      }
    }

    List<GridItemPlacement> placements = new ArrayList<>(checkedGrid.placements());
    int index = placements.indexOf(existing);
    placements.set(index, candidate.placement());
    return GridPlacementResult.accepted(
        new ItemGrid(checkedGrid.width(), checkedGrid.height(), placements,
            checkedGrid.shape()),
        candidate.placement());
  }

  @Override
  public GridPlacementResult remove(ItemGrid grid, UUID instanceId) {
    ItemGrid checkedGrid = Preconditions.checkNotNull(grid, "grid");
    Preconditions.checkNotNull(instanceId, "instanceId");
    ValidatedGrid validated = validateOrNull(checkedGrid);
    if (validated == null) return rejected(checkedGrid, GridPlacementFailure.INVALID_GRID);
    GridItemPlacement existing = validated.byIdentity().get(instanceId);
    if (existing == null) return rejected(checkedGrid, GridPlacementFailure.ITEM_NOT_FOUND);

    List<GridItemPlacement> placements = new ArrayList<>(checkedGrid.placements());
    placements.remove(existing);
    return GridPlacementResult.accepted(
        new ItemGrid(checkedGrid.width(), checkedGrid.height(), placements,
            checkedGrid.shape()), existing);
  }

  @Override
  public GridLayoutMutationResult place(ItemGridLayout layout, String gridId,
      ItemInstance item, GridPosition anchor, GridRotation rotation) {
    ItemGridLayout checkedLayout = Preconditions.checkNotNull(layout, "layout");
    Preconditions.checkNotNull(gridId, "gridId");
    Preconditions.checkNotNull(item, "item");
    Preconditions.checkNotNull(anchor, "anchor");
    Preconditions.checkNotNull(rotation, "rotation");
    if (!validLayout(checkedLayout)) return invalidLayout(checkedLayout);
    ItemGrid target = checkedLayout.grids().get(gridId);
    if (target == null) return missingGrid(checkedLayout);
    if (containsIdentity(checkedLayout, item.instanceId())) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, GridPlacementFailure.DUPLICATE_INSTANCE);
    }
    GridPlacementResult placed = place(target, item, anchor, rotation);
    if (!placed.accepted()) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, placed.rejection().orElseThrow());
    }
    return GridLayoutMutationResult.accepted(replaceGrid(checkedLayout, gridId, placed.grid()),
        placed.acceptedPlacement().orElseThrow());
  }

  @Override
  public GridLayoutMutationResult autoPlace(
      ItemGridLayout layout, String gridId, ItemInstance item) {
    ItemGridLayout checkedLayout = Preconditions.checkNotNull(layout, "layout");
    Preconditions.checkNotNull(gridId, "gridId");
    Preconditions.checkNotNull(item, "item");
    if (!validLayout(checkedLayout)) return invalidLayout(checkedLayout);
    ItemGrid target = checkedLayout.grids().get(gridId);
    if (target == null) return missingGrid(checkedLayout);
    if (containsIdentity(checkedLayout, item.instanceId())) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, GridPlacementFailure.DUPLICATE_INSTANCE);
    }
    GridPlacementResult placed = autoPlace(target, item);
    if (!placed.accepted()) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, placed.rejection().orElseThrow());
    }
    return GridLayoutMutationResult.accepted(replaceGrid(checkedLayout, gridId, placed.grid()),
        placed.acceptedPlacement().orElseThrow());
  }

  @Override
  public GridLayoutMutationResult remove(
      ItemGridLayout layout, String gridId, UUID instanceId) {
    ItemGridLayout checkedLayout = Preconditions.checkNotNull(layout, "layout");
    Preconditions.checkNotNull(gridId, "gridId");
    Preconditions.checkNotNull(instanceId, "instanceId");
    if (!validLayout(checkedLayout)) return invalidLayout(checkedLayout);
    ItemGrid target = checkedLayout.grids().get(gridId);
    if (target == null) return missingGrid(checkedLayout);
    GridPlacementResult removed = remove(target, instanceId);
    if (!removed.accepted()) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, removed.rejection().orElseThrow());
    }
    return GridLayoutMutationResult.accepted(replaceGrid(checkedLayout, gridId, removed.grid()),
        removed.acceptedPlacement().orElseThrow());
  }

  @Override
  public GridLayoutMutationResult transfer(ItemGridLayout layout, String sourceGridId,
      String targetGridId, UUID instanceId, GridPosition anchor, GridRotation rotation) {
    ItemGridLayout checkedLayout = Preconditions.checkNotNull(layout, "layout");
    Preconditions.checkNotNull(sourceGridId, "sourceGridId");
    Preconditions.checkNotNull(targetGridId, "targetGridId");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(anchor, "anchor");
    Preconditions.checkNotNull(rotation, "rotation");
    if (!validLayout(checkedLayout)) return invalidLayout(checkedLayout);
    ItemGrid source = checkedLayout.grids().get(sourceGridId);
    ItemGrid target = checkedLayout.grids().get(targetGridId);
    if (source == null || target == null) {
      return missingGrid(checkedLayout);
    }
    GridItemPlacement existing = source.find(instanceId).orElse(null);
    if (existing == null) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, GridPlacementFailure.ITEM_NOT_FOUND);
    }

    if (sourceGridId.equals(targetGridId)) {
      GridPlacementResult moved = move(source, instanceId, anchor, rotation);
      if (!moved.accepted()) {
        return GridLayoutMutationResult.rejected(
            checkedLayout, moved.rejection().orElseThrow());
      }
      Map<String, ItemGrid> grids = new LinkedHashMap<>(checkedLayout.grids());
      grids.put(sourceGridId, moved.grid());
      return GridLayoutMutationResult.accepted(new ItemGridLayout(grids),
          moved.acceptedPlacement().orElseThrow());
    }

    ValidatedGrid targetState = validateOrNull(target);
    Candidate candidate = candidate(target, existing.item(), anchor, rotation,
        existing.footprint());
    if (candidate.failure() != null) {
      return GridLayoutMutationResult.rejected(checkedLayout, candidate.failure());
    }
    if (!Collections.disjoint(targetState.byCell().keySet(), candidate.cells())) {
      return GridLayoutMutationResult.rejected(
          checkedLayout, GridPlacementFailure.OCCUPIED);
    }

    List<GridItemPlacement> sourcePlacements = new ArrayList<>(source.placements());
    sourcePlacements.remove(existing);
    List<GridItemPlacement> targetPlacements = new ArrayList<>(target.placements());
    targetPlacements.add(candidate.placement());
    Map<String, ItemGrid> grids = new LinkedHashMap<>(checkedLayout.grids());
    grids.put(sourceGridId, new ItemGrid(source.width(), source.height(),
        sourcePlacements, source.shape()));
    grids.put(targetGridId, new ItemGrid(target.width(), target.height(),
        targetPlacements, target.shape()));
    return GridLayoutMutationResult.accepted(new ItemGridLayout(grids), candidate.placement());
  }

  private boolean validLayout(ItemGridLayout layout) {
    return layout.grids().values().stream().allMatch(grid -> validateOrNull(grid) != null);
  }

  private static boolean containsIdentity(ItemGridLayout layout, UUID instanceId) {
    return layout.grids().values().stream()
        .flatMap(grid -> grid.placements().stream())
        .anyMatch(placement -> placement.item().instanceId().equals(instanceId));
  }

  private static ItemGridLayout replaceGrid(
      ItemGridLayout layout, String gridId, ItemGrid grid) {
    Map<String, ItemGrid> grids = new LinkedHashMap<>(layout.grids());
    grids.put(gridId, grid);
    return new ItemGridLayout(grids);
  }

  private static GridLayoutMutationResult invalidLayout(ItemGridLayout layout) {
    return GridLayoutMutationResult.rejected(layout, GridPlacementFailure.INVALID_GRID);
  }

  private static GridLayoutMutationResult missingGrid(ItemGridLayout layout) {
    return GridLayoutMutationResult.rejected(layout, GridPlacementFailure.GRID_NOT_FOUND);
  }

  private ValidatedGrid requireValid(ItemGrid grid) {
    ItemGrid checkedGrid = Preconditions.checkNotNull(grid, "grid");
    ValidatedGrid validated = validateOrNull(checkedGrid);
    if (validated == null) {
      throw new IllegalArgumentException("grid contains an invalid or overlapping placement");
    }
    return validated;
  }

  private ValidatedGrid validateOrNull(ItemGrid grid) {
    Map<UUID, GridItemPlacement> byIdentity = new LinkedHashMap<>();
    Map<GridPosition, GridItemPlacement> byCell = new LinkedHashMap<>();
    for (GridItemPlacement placement : grid.placements()) {
      Candidate candidate = candidate(grid, placement.item(), placement.anchor(),
          placement.rotation(), placement.footprint());
      if (candidate.failure() != null) return null;
      if (byIdentity.putIfAbsent(placement.item().instanceId(), placement) != null) return null;
      for (GridPosition cell : candidate.cells()) {
        if (byCell.putIfAbsent(cell, placement) != null) return null;
      }
    }
    return new ValidatedGrid(Map.copyOf(byIdentity), Map.copyOf(byCell));
  }

  private @Nullable ItemFootprint registeredFootprint(ItemInstance item) {
    ItemDefinition definition = items.find(item.itemId()).orElse(null);
    return definition == null ? null : definition.footprint();
  }

  private Candidate candidate(ItemGrid grid, ItemInstance item, GridPosition anchor,
      GridRotation rotation, @Nullable ItemFootprint footprint) {
    ItemDefinition definition = items.find(item.itemId()).orElse(null);
    if (definition == null) return Candidate.failed(GridPlacementFailure.UNKNOWN_ITEM);
    if (item.quantity() > definition.stackSize()) {
      return Candidate.failed(GridPlacementFailure.INVALID_PHYSICAL_STACK);
    }
    if (footprint == null) return Candidate.failed(GridPlacementFailure.UNKNOWN_ITEM);
    // A placement snapshots its trusted footprint so a registry change cannot
    // silently move cells. Until an explicit repack migration rewrites the
    // layout, a stale or forged snapshot is rejected fail-closed.
    if (!definition.footprint().equals(footprint)) {
      return Candidate.failed(GridPlacementFailure.FOOTPRINT_MISMATCH);
    }
    if (!footprint.supports(rotation)) {
      return Candidate.failed(GridPlacementFailure.ROTATION_NOT_ALLOWED);
    }
    int width = footprint.width(rotation);
    int height = footprint.height(rotation);
    if ((long) anchor.column() + width > grid.width()
        || (long) anchor.row() + height > grid.height()) {
      return Candidate.failed(GridPlacementFailure.OUT_OF_BOUNDS);
    }
    Set<GridPosition> cells = new LinkedHashSet<>(footprint.area());
    String compartment = null;
    for (int row = anchor.row(); row < anchor.row() + height; row++) {
      for (int column = anchor.column(); column < anchor.column() + width; column++) {
        GridPosition cell = new GridPosition(column, row);
        String cellCompartment = grid.shape().compartmentAt(cell).orElse(null);
        if (cellCompartment == null) {
          return Candidate.failed(GridPlacementFailure.BLOCKED_CELL);
        }
        if (compartment != null && !compartment.equals(cellCompartment)) {
          return Candidate.failed(GridPlacementFailure.COMPARTMENT_BOUNDARY);
        }
        compartment = cellCompartment;
        cells.add(cell);
      }
    }
    return new Candidate(new GridItemPlacement(item, anchor, rotation, footprint),
        Collections.unmodifiableSet(cells), null);
  }

  private static GridPlacementResult rejected(ItemGrid grid, GridPlacementFailure failure) {
    return GridPlacementResult.rejected(grid, failure);
  }

  private record ValidatedGrid(
      Map<UUID, GridItemPlacement> byIdentity,
      Map<GridPosition, GridItemPlacement> byCell) {
  }

  private record Candidate(
      @Nullable GridItemPlacement placement,
      Set<GridPosition> cells,
      @Nullable GridPlacementFailure failure) {

    private static Candidate failed(GridPlacementFailure failure) {
      return new Candidate(null, Set.of(), failure);
    }
  }
}
