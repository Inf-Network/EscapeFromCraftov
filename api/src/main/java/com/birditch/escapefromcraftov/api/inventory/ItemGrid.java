package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persistable anchor-only snapshot of a shaped cargo region.
 *
 * <p>This type intentionally has no cell array. A multi-cell item occurs once
 * in {@link #placements}; occupancy is reconstructed by {@link ItemGridService}
 * from the footprint snapshot stored on that placement.</p>
 */
public record ItemGrid(
    int width,
    int height,
    List<GridItemPlacement> placements,
    GridShape shape) {

  public ItemGrid {
    Preconditions.checkArgument(width >= 1 && width <= GridShape.MAX_WIDTH,
        "width must be between 1 and 9");
    Preconditions.checkArgument(height >= 1 && height <= GridShape.MAX_HEIGHT,
        "height must be between 1 and 6");
    Preconditions.checkNotNull(shape, "shape");
    Preconditions.checkArgument(shape.width() == width && shape.height() == height,
        "shape dimensions must match the grid");
    placements = List.copyOf(Preconditions.checkNotNull(placements, "placements"));
    Preconditions.checkArgument(placements.size() <= shape.allowedCellCount(),
        "grid cannot have more anchors than allowed cells");

    Set<UUID> identities = new HashSet<>();
    Set<GridPosition> anchors = new HashSet<>();
    for (GridItemPlacement placement : placements) {
      Preconditions.checkNotNull(placement, "placement");
      Preconditions.checkArgument(shape.compartmentAt(placement.anchor()).isPresent(),
          "placement anchor must be on an allowed grid cell");
      Preconditions.checkArgument(identities.add(placement.item().instanceId()),
          "duplicate physical item instance");
      Preconditions.checkArgument(anchors.add(placement.anchor()),
          "duplicate placement anchor");
    }
  }

  /** Compatibility constructor for the original full-rectangle grid model. */
  public ItemGrid(int width, int height, List<GridItemPlacement> placements) {
    this(width, height, placements, GridShape.rectangle(width, height));
  }

  public static ItemGrid empty(int width, int height) {
    return new ItemGrid(width, height, List.of());
  }

  public static ItemGrid empty(GridShape shape) {
    GridShape checked = Preconditions.checkNotNull(shape, "shape");
    return new ItemGrid(checked.width(), checked.height(), List.of(), checked);
  }

  public int area() {
    return shape.allowedCellCount();
  }

  public Optional<GridItemPlacement> find(UUID instanceId) {
    Preconditions.checkNotNull(instanceId, "instanceId");
    return placements.stream()
        .filter(placement -> placement.item().instanceId().equals(instanceId))
        .findFirst();
  }
}
