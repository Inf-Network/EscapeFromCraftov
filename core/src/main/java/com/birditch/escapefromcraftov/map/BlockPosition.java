package com.birditch.escapefromcraftov.map;

public record BlockPosition(int x, int y, int z) {

  public double horizontalDistance(BlockPosition other) {
    long dx = (long) x - other.x;
    long dz = (long) z - other.z;
    return Math.sqrt(dx * dx + dz * dz);
  }

  public double distanceSquared(BlockPosition other) {
    long dx = (long) x - other.x;
    long dy = (long) y - other.y;
    long dz = (long) z - other.z;
    return (double) dx * dx + (double) dy * dy + (double) dz * dz;
  }

  public BlockPosition add(int dx, int dy, int dz) {
    return new BlockPosition(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
  }
}
