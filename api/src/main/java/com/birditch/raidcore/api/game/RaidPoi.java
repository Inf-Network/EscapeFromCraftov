package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;

/** Immutable point of interest metadata captured for one raid instance. */
public record RaidPoi(
    String id,
    String displayName,
    int tier,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ) {

  public RaidPoi {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkArgument(tier >= 1 && tier <= 4, "tier must be 1..4");
    Preconditions.checkArgument(maxX >= minX && maxY >= minY && maxZ >= minZ,
        "invalid POI bounds");
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0
        && y >= minY && y < maxY + 1.0
        && z >= minZ && z < maxZ + 1.0;
  }
}
