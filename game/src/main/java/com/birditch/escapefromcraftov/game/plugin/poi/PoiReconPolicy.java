package com.birditch.escapefromcraftov.game.plugin.poi;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.RaidActionRuntimePolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Pure selection and geometry rules for the one bounded POI target per raid. */
final class PoiReconPolicy {

  private static final double SUPPORT_BLOCK_EPSILON = 1.0e-7;
  static final long MAXIMUM_DISPLAY_DISTANCE_METERS =
      PoiReconLookup.View.MAXIMUM_DISPLAY_DISTANCE_METERS;

  private PoiReconPolicy() {}

  static Optional<CraftovPoi> select(CraftovGameId gameId, long mapRevision,
      List<CraftovPoi> pois) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkArgument(mapRevision >= 0, "mapRevision must not be negative");
    Preconditions.checkNotNull(pois, "pois");
    int highestTier = pois.stream().mapToInt(CraftovPoi::tier).max().orElse(0);
    if (highestTier == 0) return Optional.empty();
    Comparator<CraftovPoi> stableTieOrder = (first, second) -> {
      long firstHash = tieHash(gameId, mapRevision, first.id());
      long secondHash = tieHash(gameId, mapRevision, second.id());
      int compared = Long.compareUnsigned(firstHash, secondHash);
      return compared != 0 ? compared : first.id().compareTo(second.id());
    };
    return pois.stream().filter(poi -> poi.tier() == highestTier).min(stableTieOrder);
  }

  /**
   * Authored regions may be a volume or only the supporting ground layer.
   * Check the exact feet first, then exactly one block below; never flatten Y.
   */
  static boolean reached(CraftovPoi poi, double feetX, double feetY, double feetZ,
      boolean onGround) {
    Preconditions.checkNotNull(poi, "poi");
    if (!finite(feetX) || !finite(feetY) || !finite(feetZ)) return false;
    if (poi.contains(feetX, feetY, feetZ)) return true;
    if (!onGround) return false;
    double supportX = Math.floor(feetX);
    double supportY = Math.floor(feetY - SUPPORT_BLOCK_EPSILON);
    double supportZ = Math.floor(feetZ);
    return poi.contains(supportX, supportY, supportZ);
  }

  static Optional<Measurement> measure(CraftovPoi poi, double x, double z) {
    Preconditions.checkNotNull(poi, "poi");
    if (!finite(x) || !finite(z)) return Optional.empty();
    double centerX = center(poi.minX(), poi.maxX());
    double centerZ = center(poi.minZ(), poi.maxZ());
    double dx = centerX - x;
    double dz = centerZ - z;
    Distance distance = roundedDistance(dx, dz);
    return Optional.of(new Measurement(direction(dx, dz), distance.meters(), distance.capped()));
  }

  private static long tieHash(CraftovGameId gameId, long mapRevision, String poiId) {
    return RaidActionRuntimePolicy.sourceSeed(gameId,
        "poi-recon:" + mapRevision + ':' + Preconditions.checkNotNull(poiId, "poiId"));
  }

  private static double center(int minimum, int maximum) {
    return minimum + (((long) maximum - minimum + 1L) / 2.0);
  }

  private static Distance roundedDistance(double dx, double dz) {
    double distance = Math.hypot(dx, dz);
    if (!Double.isFinite(distance) || distance > MAXIMUM_DISPLAY_DISTANCE_METERS) {
      return new Distance(MAXIMUM_DISPLAY_DISTANCE_METERS, true);
    }
    return new Distance(Math.max(0L, Math.round(distance)), false);
  }

  private static Direction direction(double dx, double dz) {
    if (dx == 0.0 && dz == 0.0) return Direction.ARRIVED;
    double angle = Math.atan2(dz, dx);
    int octant = Math.floorMod((int) Math.floor(
        (angle + Math.PI / 8.0) / (Math.PI / 4.0)), 8);
    return switch (octant) {
      case 0 -> Direction.EAST;
      case 1 -> Direction.SOUTH_EAST;
      case 2 -> Direction.SOUTH;
      case 3 -> Direction.SOUTH_WEST;
      case 4 -> Direction.WEST;
      case 5 -> Direction.NORTH_WEST;
      case 6 -> Direction.NORTH;
      case 7 -> Direction.NORTH_EAST;
      default -> throw new IllegalStateException("unreachable octant " + octant);
    };
  }

  private static boolean finite(double value) {
    return Double.isFinite(value);
  }

  enum Direction {
    ARRIVED("已到达"),
    NORTH("北"),
    NORTH_EAST("东北"),
    EAST("东"),
    SOUTH_EAST("东南"),
    SOUTH("南"),
    SOUTH_WEST("西南"),
    WEST("西"),
    NORTH_WEST("西北");

    private final String displayName;

    Direction(String displayName) {
      this.displayName = displayName;
    }

    String displayName() {
      return displayName;
    }
  }

  record Measurement(Direction direction, long distanceMeters, boolean distanceCapped) {
    Measurement {
      Preconditions.checkNotNull(direction, "direction");
      Preconditions.checkArgument(distanceMeters >= 0, "distanceMeters must not be negative");
      Preconditions.checkArgument(distanceMeters <= MAXIMUM_DISPLAY_DISTANCE_METERS,
          "distanceMeters exceeds display cap");
    }
  }

  private record Distance(long meters, boolean capped) {}
}
