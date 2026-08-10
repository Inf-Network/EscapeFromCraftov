package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;

/// Inclusive, axis-aligned block region.
public record CuboidBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

  public CuboidBounds {
    Preconditions.checkArgument(maxX >= minX, "maxX must not be below minX");
    Preconditions.checkArgument(maxY >= minY, "maxY must not be below minY");
    Preconditions.checkArgument(maxZ >= minZ, "maxZ must not be below minZ");
  }

  public static CuboidBounds between(BlockPosition first, BlockPosition second) {
    Preconditions.checkNotNull(first, "first");
    Preconditions.checkNotNull(second, "second");
    return new CuboidBounds(Math.min(first.x(), second.x()), Math.min(first.y(), second.y()),
        Math.min(first.z(), second.z()), Math.max(first.x(), second.x()),
        Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
  }

  public int width() { return maxX - minX + 1; }
  public int height() { return maxY - minY + 1; }
  public int depth() { return maxZ - minZ + 1; }
  public long volume() { return (long) width() * height() * depth(); }

  public BlockPosition center() {
    return new BlockPosition(minX + (maxX - minX) / 2, minY + (maxY - minY) / 2,
        minZ + (maxZ - minZ) / 2);
  }

  public boolean contains(BlockPosition point) {
    return point.x() >= minX && point.x() <= maxX
        && point.y() >= minY && point.y() <= maxY
        && point.z() >= minZ && point.z() <= maxZ;
  }

  public boolean contains(CuboidBounds other) {
    return other.minX >= minX && other.maxX <= maxX
        && other.minY >= minY && other.maxY <= maxY
        && other.minZ >= minZ && other.maxZ <= maxZ;
  }

  public boolean intersects(CuboidBounds other) {
    return minX <= other.maxX && maxX >= other.minX
        && minY <= other.maxY && maxY >= other.minY
        && minZ <= other.maxZ && maxZ >= other.minZ;
  }

  public CuboidBounds translate(int dx, int dy, int dz) {
    return new CuboidBounds(Math.addExact(minX, dx), Math.addExact(minY, dy),
        Math.addExact(minZ, dz), Math.addExact(maxX, dx), Math.addExact(maxY, dy),
        Math.addExact(maxZ, dz));
  }
}
