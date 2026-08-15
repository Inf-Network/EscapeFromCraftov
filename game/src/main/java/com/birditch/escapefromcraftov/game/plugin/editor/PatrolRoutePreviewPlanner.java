package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.BlockPosition;
import java.util.ArrayList;
import java.util.List;

/** Produces one globally bounded particle frame for an ordered cyclic patrol route. */
final class PatrolRoutePreviewPlanner {

  static final int MAXIMUM_SAMPLES_PER_FRAME = 256;

  private PatrolRoutePreviewPlanner() {}

  static List<Sample> plan(List<BlockPosition> route) {
    Preconditions.checkNotNull(route, "route");
    Preconditions.checkArgument(route.size() <= CraftovAiZone.MAXIMUM_PATROL_WAYPOINTS,
        "route exceeds authored waypoint limit");
    if (route.isEmpty()) return List.of();
    List<Sample> result = new ArrayList<>(MAXIMUM_SAMPLES_PER_FRAME);
    for (int index = 0; index < route.size(); index++) {
      BlockPosition point = route.get(index);
      Kind kind = index == 0 ? Kind.START : Kind.WAYPOINT;
      add(result, point.x() + 0.5, point.y() + 0.2, point.z() + 0.5, kind);
      add(result, point.x() + 0.5, point.y() + 1.0, point.z() + 0.5, kind);
    }
    if (route.size() == 1) return List.copyOf(result);

    int samplesPerSegment = Math.min(12,
        (MAXIMUM_SAMPLES_PER_FRAME - result.size()) / route.size());
    for (int index = 0; index < route.size(); index++) {
      BlockPosition from = route.get(index);
      BlockPosition to = route.get((index + 1) % route.size());
      int lineSamples = Math.max(1, samplesPerSegment - 2);
      for (int sample = 1; sample <= lineSamples; sample++) {
        double progress = sample / (double) (lineSamples + 1);
        add(result, interpolate(from.x() + 0.5, to.x() + 0.5, progress),
            interpolate(from.y() + 0.45, to.y() + 0.45, progress),
            interpolate(from.z() + 0.5, to.z() + 0.5, progress), Kind.PATH);
      }
      if (samplesPerSegment >= 3) addArrow(result, from, to);
    }
    return List.copyOf(result);
  }

  private static void addArrow(List<Sample> result, BlockPosition from, BlockPosition to) {
    double dx = to.x() - from.x();
    double dz = to.z() - from.z();
    double length = Math.hypot(dx, dz);
    if (length < 1.0e-6) {
      dx = 1;
      dz = 0;
      length = 1;
    }
    dx /= length;
    dz /= length;
    double tipX = to.x() + 0.5;
    double tipY = to.y() + 0.45;
    double tipZ = to.z() + 0.5;
    double baseX = tipX - dx * 0.65;
    double baseZ = tipZ - dz * 0.65;
    add(result, baseX + dz * 0.35, tipY, baseZ - dx * 0.35, Kind.ARROW);
    add(result, baseX - dz * 0.35, tipY, baseZ + dx * 0.35, Kind.ARROW);
  }

  private static void add(List<Sample> result, double x, double y, double z, Kind kind) {
    if (result.size() < MAXIMUM_SAMPLES_PER_FRAME) {
      result.add(new Sample(x, y, z, kind));
    }
  }

  private static double interpolate(double from, double to, double progress) {
    return from + (to - from) * progress;
  }

  enum Kind {
    START,
    WAYPOINT,
    PATH,
    ARROW
  }

  record Sample(double x, double y, double z, Kind kind) {}
}
