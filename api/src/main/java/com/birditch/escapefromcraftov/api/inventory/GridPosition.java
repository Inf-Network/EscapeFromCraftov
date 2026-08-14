package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/** Zero-based cargo grid position, measured from the top-left corner. */
public record GridPosition(int column, int row) {

  public GridPosition {
    Preconditions.checkArgument(column >= 0, "column must not be negative");
    Preconditions.checkArgument(row >= 0, "row must not be negative");
  }
}
