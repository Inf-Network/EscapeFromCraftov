package com.birditch.raidcore.automap;

/// Integer block position stored without any Bukkit dependency.
public record MapPoint(int x, int y, int z) {

  public double horizontalDistance(MapPoint other) {
    long dx = (long) x - other.x;
    long dz = (long) z - other.z;
    return Math.sqrt(dx * dx + dz * dz);
  }
}
