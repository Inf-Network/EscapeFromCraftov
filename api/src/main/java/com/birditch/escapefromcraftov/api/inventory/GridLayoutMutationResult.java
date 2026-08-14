package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Result of an atomic layout-level place, move, remove, or transfer operation. */
public record GridLayoutMutationResult(
    boolean accepted,
    ItemGridLayout layout,
    @Nullable GridItemPlacement placement,
    @Nullable GridPlacementFailure failure) {

  public GridLayoutMutationResult {
    Preconditions.checkNotNull(layout, "layout");
    Preconditions.checkArgument(accepted
            ? placement != null && failure == null
            : placement == null && failure != null,
        "accepted results require only a placement; rejected results require only a failure");
  }

  public static GridLayoutMutationResult accepted(
      ItemGridLayout layout, GridItemPlacement placement) {
    return new GridLayoutMutationResult(true, layout, placement, null);
  }

  public static GridLayoutMutationResult rejected(
      ItemGridLayout layout, GridPlacementFailure failure) {
    return new GridLayoutMutationResult(false, layout, null,
        Preconditions.checkNotNull(failure, "failure"));
  }

  public Optional<GridPlacementFailure> rejection() {
    return Optional.ofNullable(failure);
  }
}
