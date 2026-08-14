package com.birditch.escapefromcraftov.api.inventory;

/** Stable rejection codes for atomic cargo-grid mutations. */
public enum GridPlacementFailure {
  UNKNOWN_ITEM,
  INVALID_PHYSICAL_STACK,
  FOOTPRINT_MISMATCH,
  DUPLICATE_INSTANCE,
  ROTATION_NOT_ALLOWED,
  OUT_OF_BOUNDS,
  BLOCKED_CELL,
  COMPARTMENT_BOUNDARY,
  OCCUPIED,
  NO_SPACE,
  ITEM_NOT_FOUND,
  GRID_NOT_FOUND,
  INVALID_GRID
}
