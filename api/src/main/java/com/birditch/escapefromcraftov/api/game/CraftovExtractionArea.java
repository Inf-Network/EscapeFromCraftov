package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Inclusive extraction cuboid captured for one craftov instance.
public record CraftovExtractionArea(
    ExtractionPointId pointId,
    String displayName,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    @org.jspecify.annotations.Nullable CraftovExtractionRouteScope routeScope) {

  public CraftovExtractionArea {
    Preconditions.checkNotNull(pointId, "pointId");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(maxX >= minX && maxY >= minY && maxZ >= minZ,
        "invalid extraction bounds");
  }

  public CraftovExtractionArea(ExtractionPointId pointId, String displayName,
      int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    this(pointId, displayName, minX, minY, minZ, maxX, maxY, maxZ, null);
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0
        && y >= minY && y < maxY + 1.0
        && z >= minZ && z < maxZ + 1.0;
  }
}
