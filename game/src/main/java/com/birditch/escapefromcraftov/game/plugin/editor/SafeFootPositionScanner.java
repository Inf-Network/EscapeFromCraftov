package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import com.birditch.escapefromcraftov.map.CuboidBounds;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/** Pure, bounded candidate strategy for finding a player's foot position in a cuboid. */
final class SafeFootPositionScanner {

  private SafeFootPositionScanner() {}

  static boolean hasSafePosition(CuboidBounds bounds, int minimumFeetY, int maximumFeetY,
      int maximumChecks, Predicate<BlockPosition> safePosition) {
    Preconditions.checkNotNull(safePosition, "safePosition");
    for (BlockPosition candidate : candidates(bounds, minimumFeetY, maximumFeetY,
        maximumChecks)) {
      if (safePosition.test(candidate)) return true;
    }
    return false;
  }

  static List<BlockPosition> candidates(CuboidBounds bounds, int minimumFeetY,
      int maximumFeetY, int maximumChecks) {
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkArgument(maximumChecks > 0, "maximumChecks must be positive");
    int minimumY = Math.max(bounds.minY(), minimumFeetY);
    int maximumY = Math.min(bounds.maxY(), maximumFeetY);
    if (minimumY > maximumY) return List.of();

    long width = (long) bounds.maxX() - bounds.minX() + 1L;
    long height = (long) maximumY - minimumY + 1L;
    long depth = (long) bounds.maxZ() - bounds.minZ() + 1L;
    long volume = saturatedMultiply(saturatedMultiply(width, height), depth);
    if (volume <= maximumChecks) {
      List<BlockPosition> result = new ArrayList<>((int) volume);
      for (int y = minimumY; ; y++) {
        for (int x = bounds.minX(); ; x++) {
          for (int z = bounds.minZ(); ; z++) {
            result.add(new BlockPosition(x, y, z));
            if (z == bounds.maxZ()) break;
          }
          if (x == bounds.maxX()) break;
        }
        if (y == maximumY) break;
      }
      return List.copyOf(result);
    }

    int xSamples = 1;
    int ySamples = 1;
    int zSamples = 1;
    while (true) {
      int dimension = bestDimension(width, height, depth, xSamples, ySamples, zSamples,
          maximumChecks);
      if (dimension < 0) break;
      int nextX = xSamples + (dimension == 0 ? 1 : 0);
      int nextY = ySamples + (dimension == 1 ? 1 : 0);
      int nextZ = zSamples + (dimension == 2 ? 1 : 0);
      xSamples = nextX;
      ySamples = nextY;
      zSamples = nextZ;
    }

    LinkedHashSet<BlockPosition> result = new LinkedHashSet<>(maximumChecks);
    addCandidate(result, midpoint(bounds.minX(), bounds.maxX()), midpoint(minimumY, maximumY),
        midpoint(bounds.minZ(), bounds.maxZ()), maximumChecks);
    for (int yIndex = 0; yIndex < ySamples && result.size() < maximumChecks; yIndex++) {
      int y = sample(minimumY, maximumY, yIndex, ySamples);
      for (int xIndex = 0; xIndex < xSamples && result.size() < maximumChecks; xIndex++) {
        int x = sample(bounds.minX(), bounds.maxX(), xIndex, xSamples);
        for (int zIndex = 0; zIndex < zSamples && result.size() < maximumChecks; zIndex++) {
          addCandidate(result, x, y,
              sample(bounds.minZ(), bounds.maxZ(), zIndex, zSamples), maximumChecks);
        }
      }
    }
    return List.copyOf(result);
  }

  private static int bestDimension(long width, long height, long depth,
      int xSamples, int ySamples, int zSamples, int maximumChecks) {
    double best = -1;
    int dimension = -1;
    if (xSamples < width
        && (long) (xSamples + 1) * ySamples * zSamples <= maximumChecks) {
      best = (double) width / xSamples;
      dimension = 0;
    }
    if (ySamples < height && (long) xSamples * (ySamples + 1) * zSamples <= maximumChecks
        && (double) height / ySamples > best) {
      best = (double) height / ySamples;
      dimension = 1;
    }
    if (zSamples < depth && (long) xSamples * ySamples * (zSamples + 1) <= maximumChecks
        && (double) depth / zSamples > best) {
      dimension = 2;
    }
    return dimension;
  }

  private static int sample(int minimum, int maximum, int index, int samples) {
    if (samples == 1) return midpoint(minimum, maximum);
    long offset = (long) index * ((long) maximum - minimum) / (samples - 1L);
    return (int) (minimum + offset);
  }

  private static int midpoint(int minimum, int maximum) {
    return (int) (((long) minimum + maximum) / 2L);
  }

  private static void addCandidate(LinkedHashSet<BlockPosition> result, int x, int y, int z,
      int maximumChecks) {
    if (result.size() < maximumChecks) result.add(new BlockPosition(x, y, z));
  }

  private static long saturatedMultiply(long first, long second) {
    if (first == 0 || second == 0) return 0;
    if (first > Long.MAX_VALUE / second) return Long.MAX_VALUE;
    return first * second;
  }
}
