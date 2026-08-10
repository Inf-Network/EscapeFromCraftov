package com.birditch.raidcore.automap;

import com.birditch.raidcore.common.check.Preconditions;

/// Finite X/Z analysis rectangle. Infinite-world scans are never allowed.
public record MapBounds(int minX, int maxX, int minZ, int maxZ) {

  public MapBounds {
    Preconditions.checkArgument(maxX >= minX, "maxX must not be below minX");
    Preconditions.checkArgument(maxZ >= minZ, "maxZ must not be below minZ");
  }

  public static MapBounds around(int centerX, int centerZ, int radius) {
    Preconditions.checkArgument(radius > 0, "radius must be positive");
    return new MapBounds(centerX - radius, centerX + radius, centerZ - radius, centerZ + radius);
  }

  public int width() { return maxX - minX + 1; }
  public int depth() { return maxZ - minZ + 1; }
  public long area() { return (long) width() * depth(); }
  public int centerX() { return minX + (maxX - minX) / 2; }
  public int centerZ() { return minZ + (maxZ - minZ) / 2; }

  public double edgeDistance(int x, int z) {
    return Math.min(Math.min(x - minX, maxX - x), Math.min(z - minZ, maxZ - z));
  }
}
