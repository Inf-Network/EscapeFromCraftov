package com.birditch.raidcore.api.game;

import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.common.check.Preconditions;

/// Inclusive extraction cuboid captured for one raid instance.
public record RaidExtractionArea(
    ExtractionPointId pointId,
    String displayName,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ) {

  public RaidExtractionArea {
    Preconditions.checkNotNull(pointId, "pointId");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(maxX >= minX && maxY >= minY && maxZ >= minZ,
        "invalid extraction bounds");
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0
        && y >= minY && y < maxY + 1.0
        && z >= minZ && z < maxZ + 1.0;
  }
}
