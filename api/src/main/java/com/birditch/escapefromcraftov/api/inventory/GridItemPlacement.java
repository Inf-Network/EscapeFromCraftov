package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemFootprint;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/**
 * The single persisted anchor for one physical item stack in a cargo grid.
 * Covered cells are derived from the persisted footprint snapshot and are never
 * stored as cloned or synthetic item instances.
 */
public record GridItemPlacement(
    ItemInstance item,
    GridPosition anchor,
    GridRotation rotation,
    ItemFootprint footprint) {

  public GridItemPlacement {
    Preconditions.checkNotNull(item, "item");
    Preconditions.checkNotNull(anchor, "anchor");
    Preconditions.checkNotNull(rotation, "rotation");
    Preconditions.checkNotNull(footprint, "footprint");
  }

  /** Compatibility constructor for original one-cell placement snapshots. */
  public GridItemPlacement(ItemInstance item, GridPosition anchor, GridRotation rotation) {
    this(item, anchor, rotation, ItemFootprint.SINGLE_CELL);
  }
}
