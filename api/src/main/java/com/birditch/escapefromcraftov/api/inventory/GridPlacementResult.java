package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Result of an atomic place, move, or remove operation. */
public record GridPlacementResult(
    boolean accepted,
    ItemGrid grid,
    @Nullable GridItemPlacement placement,
    @Nullable GridPlacementFailure failure) {

  public GridPlacementResult {
    Preconditions.checkNotNull(grid, "grid");
    Preconditions.checkArgument(accepted
            ? placement != null && failure == null
            : placement == null && failure != null,
        "accepted results require only a placement; rejected results require only a failure");
  }

  public static GridPlacementResult accepted(ItemGrid grid, GridItemPlacement placement) {
    return new GridPlacementResult(true, grid, placement, null);
  }

  public static GridPlacementResult rejected(ItemGrid grid, GridPlacementFailure failure) {
    return new GridPlacementResult(false, grid, null,
        Preconditions.checkNotNull(failure, "failure"));
  }

  public Optional<GridItemPlacement> acceptedPlacement() {
    return Optional.ofNullable(placement);
  }

  public Optional<GridPlacementFailure> rejection() {
    return Optional.ofNullable(failure);
  }
}
