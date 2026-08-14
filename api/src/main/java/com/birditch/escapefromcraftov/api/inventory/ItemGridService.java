package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Pure authority for validating and atomically changing anchor-only item grids. */
public interface ItemGridService {

  /** Returns all occupied coordinates, derived without creating ghost items. */
  Set<GridPosition> occupiedCells(ItemGrid grid);

  /** Returns the one persisted anchor placement covering {@code cell}, if any. */
  Optional<GridItemPlacement> placementAt(ItemGrid grid, GridPosition cell);

  /** For an independent grid or internal layout implementation only. */
  GridPlacementResult place(ItemGrid grid, ItemInstance item, GridPosition anchor,
      GridRotation rotation);

  /** Deterministic row-major placement, trying default then rotated at each anchor. */
  /** For an independent grid or internal layout implementation only. */
  GridPlacementResult autoPlace(ItemGrid grid, ItemInstance item);

  /** Atomic move: a failed destination leaves the original grid untouched. */
  /** For an independent grid or internal layout implementation only. */
  GridPlacementResult move(ItemGrid grid, UUID instanceId, GridPosition anchor,
      GridRotation rotation);

  /** For an independent grid or internal layout implementation only. */
  GridPlacementResult remove(ItemGrid grid, UUID instanceId);

  /** Layout-authoritative place with a global physical-identity check. */
  GridLayoutMutationResult place(ItemGridLayout layout, String gridId,
      ItemInstance item, GridPosition anchor, GridRotation rotation);

  /** Layout-authoritative deterministic place with a global identity check. */
  GridLayoutMutationResult autoPlace(ItemGridLayout layout, String gridId,
      ItemInstance item);

  /** Layout-authoritative remove. */
  GridLayoutMutationResult remove(ItemGridLayout layout, String gridId, UUID instanceId);

  /** Atomic transfer; any source or target failure returns the original layout. */
  GridLayoutMutationResult transfer(ItemGridLayout layout, String sourceGridId,
      String targetGridId, UUID instanceId, GridPosition anchor, GridRotation rotation);
}
