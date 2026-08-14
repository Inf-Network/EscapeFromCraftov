package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Pure spatial fallback for maps that do not yet author spawn-to-exit routes:
/// select the enabled extraction whose X/Z centre is farthest from the team's
/// frozen spawn. Equal-distance exits are resolved by a stable team/game salt
/// so assignments remain deterministic across reloads.
final class TeamExtractionRoutePolicy {

  private TeamExtractionRoutePolicy() {}

  static Optional<ExtractionPointId> farthest(
      List<CraftovExtractionArea> areas,
      Set<ExtractionPointId> enabledPointIds,
      double spawnX,
      double spawnZ,
      String assignmentSalt) {
    Preconditions.checkNotNull(areas, "areas");
    Preconditions.checkNotNull(enabledPointIds, "enabledPointIds");
    Preconditions.checkArgument(Double.isFinite(spawnX), "spawnX must be finite");
    Preconditions.checkArgument(Double.isFinite(spawnZ), "spawnZ must be finite");
    Preconditions.checkNotNull(assignmentSalt, "assignmentSalt");
    return areas.stream()
        .filter(area -> enabledPointIds.contains(area.pointId()))
        .max(Comparator.comparingDouble(
                (CraftovExtractionArea area) -> distanceSquared(area, spawnX, spawnZ))
            .thenComparingLong(area -> tieRank(assignmentSalt, area.pointId()))
            .thenComparing(area -> area.pointId().value()))
        .map(CraftovExtractionArea::pointId);
  }

  private static double distanceSquared(CraftovExtractionArea area, double x, double z) {
    double centreX = ((double) area.minX() + area.maxX()) / 2.0;
    double centreZ = ((double) area.minZ() + area.maxZ()) / 2.0;
    double deltaX = centreX - x;
    double deltaZ = centreZ - z;
    return deltaX * deltaX + deltaZ * deltaZ;
  }

  private static long tieRank(String salt, ExtractionPointId pointId) {
    return Integer.toUnsignedLong((salt + '|' + pointId.value()).hashCode());
  }
}
