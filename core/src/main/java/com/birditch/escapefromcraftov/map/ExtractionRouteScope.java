package com.birditch.escapefromcraftov.map;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// Authored extraction visibility. A null scope on ExtractionDefinition means
/// the map predates route authoring; an explicit scope may be global, target
/// named spawn groups, or both.
public record ExtractionRouteScope(
    boolean global, List<String> eligibleSpawnGroups) {

  public ExtractionRouteScope {
    eligibleSpawnGroups = List.copyOf(Preconditions.checkNotNull(
        eligibleSpawnGroups, "eligibleSpawnGroups"));
    eligibleSpawnGroups.forEach(group -> MapIds.requireValid(group, "eligible spawn group"));
    Preconditions.checkArgument(global || !eligibleSpawnGroups.isEmpty(),
        "route scope must be global or contain at least one spawn group");
  }
}
