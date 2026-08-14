package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.LinkedHashSet;
import java.util.Set;

/// Immutable runtime form of an authored extraction route scope.
public record CraftovExtractionRouteScope(
    boolean global, Set<String> eligibleSpawnGroups) {

  public CraftovExtractionRouteScope {
    eligibleSpawnGroups = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(
        Preconditions.checkNotNull(eligibleSpawnGroups, "eligibleSpawnGroups")));
    eligibleSpawnGroups.forEach(group -> {
      Preconditions.checkNotNull(group, "eligibleSpawnGroup");
      Preconditions.checkArgument(group.matches("[a-z0-9_]+"),
          "eligible spawn group must match [a-z0-9_]+");
    });
    Preconditions.checkArgument(global || !eligibleSpawnGroups.isEmpty(),
        "route scope must be global or contain at least one spawn group");
  }

  public boolean allows(String spawnGroup) {
    return global || eligibleSpawnGroups.contains(spawnGroup);
  }
}
