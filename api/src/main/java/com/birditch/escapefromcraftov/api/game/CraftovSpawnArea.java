package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Inclusive block bounds in which a craftov spawn may choose a safe position.
public record CraftovSpawnArea(
    int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

  public CraftovSpawnArea {
    Preconditions.checkArgument(maxX >= minX, "maxX must not be below minX");
    Preconditions.checkArgument(maxY >= minY, "maxY must not be below minY");
    Preconditions.checkArgument(maxZ >= minZ, "maxZ must not be below minZ");
  }

  public boolean contains(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }
}
