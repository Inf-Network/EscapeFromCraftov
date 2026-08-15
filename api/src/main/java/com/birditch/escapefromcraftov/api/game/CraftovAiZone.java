package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;

/** Immutable AI spawn region captured for one craftov instance. */
public record CraftovAiZone(
    String id,
    String aiType,
    int minimum,
    int maximum,
    int respawnSeconds,
    double difficulty,
    String team,
    int minX,
    int minY,
    int minZ,
    int maxX,
    int maxY,
    int maxZ,
    List<CraftovAiWaypoint> patrolWaypoints) {

  public static final int MAXIMUM_PATROL_WAYPOINTS = 32;

  public CraftovAiZone {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkNotNull(aiType, "aiType");
    Preconditions.checkNotNull(team, "team");
    Preconditions.checkArgument(minimum >= 0 && maximum >= minimum,
        "invalid AI count");
    Preconditions.checkArgument(respawnSeconds >= 0, "respawnSeconds must not be negative");
    Preconditions.checkArgument(Double.isFinite(difficulty) && difficulty >= 0,
        "difficulty must be finite and non-negative");
    Preconditions.checkArgument(maxX >= minX && maxY >= minY && maxZ >= minZ,
        "invalid AI zone bounds");
    patrolWaypoints = List.copyOf(Preconditions.checkNotNull(
        patrolWaypoints, "patrolWaypoints"));
    Preconditions.checkArgument(patrolWaypoints.size() <= MAXIMUM_PATROL_WAYPOINTS,
        "patrolWaypoints must contain at most " + MAXIMUM_PATROL_WAYPOINTS + " points");
    HashSet<CraftovAiWaypoint> unique = new HashSet<>();
    for (CraftovAiWaypoint waypoint : patrolWaypoints) {
      Preconditions.checkArgument(unique.add(waypoint),
          "patrolWaypoints must not contain duplicates: " + waypoint);
      Preconditions.checkArgument(waypoint.x() >= minX && waypoint.x() <= maxX
              && waypoint.y() >= minY && waypoint.y() <= maxY
              && waypoint.z() >= minZ && waypoint.z() <= maxZ,
          "patrol waypoint must be inside AI zone bounds: " + waypoint);
    }
  }

  /** Compatibility constructor for runtime providers that predate patrol routes. */
  public CraftovAiZone(String id, String aiType, int minimum, int maximum,
      int respawnSeconds, double difficulty, String team, int minX, int minY, int minZ,
      int maxX, int maxY, int maxZ) {
    this(id, aiType, minimum, maximum, respawnSeconds, difficulty, team,
        minX, minY, minZ, maxX, maxY, maxZ, List.of());
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0
        && y >= minY && y < maxY + 1.0
        && z >= minZ && z < maxZ + 1.0;
  }
}
