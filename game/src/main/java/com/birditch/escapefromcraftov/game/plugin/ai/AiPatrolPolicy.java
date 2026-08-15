package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.api.game.CraftovAiWaypoint;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/** Pure, deterministic cursor policy for an authored cyclic patrol route. */
final class AiPatrolPolicy {

  private AiPatrolPolicy() {}

  static boolean validRoute(CraftovAiZone zone) {
    Preconditions.checkNotNull(zone, "zone");
    if (zone.patrolWaypoints().size() > CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS) return false;
    java.util.HashSet<CraftovAiWaypoint> unique = new java.util.HashSet<>();
    for (CraftovAiWaypoint waypoint : zone.patrolWaypoints()) {
      if (!unique.add(waypoint) || !zone.contains(waypoint.x(), waypoint.y(), waypoint.z())) {
        return false;
      }
    }
    return true;
  }

  static int initialCursor(CraftovAiZone zone, long spawnOrdinal) {
    Preconditions.checkNotNull(zone, "zone");
    List<CraftovAiWaypoint> route = zone.patrolWaypoints();
    if (route.isEmpty()) return -1;
    double referenceX = zone.minX() + ((long) zone.maxX() - zone.minX()) / 2.0 + 0.5;
    double referenceY = zone.minY() + ((long) zone.maxY() - zone.minY()) / 2.0;
    double referenceZ = zone.minZ() + ((long) zone.maxZ() - zone.minZ()) / 2.0 + 0.5;
    int nearest = 0;
    double nearestDistance = Double.POSITIVE_INFINITY;
    for (int index = 0; index < route.size(); index++) {
      CraftovAiWaypoint waypoint = route.get(index);
      double dx = waypoint.x() + 0.5 - referenceX;
      double dy = waypoint.y() - referenceY;
      double dz = waypoint.z() + 0.5 - referenceZ;
      double distance = dx * dx + dy * dy + dz * dz;
      if (distance < nearestDistance) {
        nearest = index;
        nearestDistance = distance;
      }
    }
    return Math.floorMod(nearest + Math.floorMod(spawnOrdinal, route.size()), route.size());
  }

  static int cursorAfter(int cursor, int routeSize, Progress progress) {
    Preconditions.checkArgument(routeSize > 0, "routeSize must be positive");
    Preconditions.checkArgument(cursor >= 0 && cursor < routeSize,
        "cursor must address the route");
    Preconditions.checkNotNull(progress, "progress");
    return progress == Progress.ARRIVED ? (cursor + 1) % routeSize : cursor;
  }

  enum Progress {
    ARRIVED,
    PATH_STARTED,
    PATH_FAILED,
    UNLOADED,
    DEFERRED,
    BUDGET_EXHAUSTED
  }
}
