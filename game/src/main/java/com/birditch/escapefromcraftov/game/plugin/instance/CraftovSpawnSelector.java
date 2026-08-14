package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnTeamRules;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// Pure deterministic team and smooth weighted round-robin selection for
/// authored craftov spawns.
final class CraftovSpawnSelector {

  private CraftovSpawnSelector() {}

  static Optional<CraftovSpawnLocation> select(
      List<CraftovSpawnLocation> spawns, int teamId, long ticket) {
    return select(spawns, teamId, ticket, Set.of());
  }

  /// Reserves frozen authored spawns across opposing teams. When any eligible
  /// point remains, selection is restricted to unused points; deterministic
  /// weighted reuse begins only after the candidate pool is exhausted.
  static Optional<CraftovSpawnLocation> select(
      List<CraftovSpawnLocation> spawns, int teamId, long ticket,
      Set<String> reservedSpawnIds) {
    Preconditions.checkNotNull(spawns, "spawns");
    Preconditions.checkNotNull(reservedSpawnIds, "reservedSpawnIds");
    Preconditions.checkArgument(ticket >= 0, "ticket must not be negative");
    List<CraftovSpawnLocation> dedicated = spawns.stream()
        .filter(spawn -> !CraftovSpawnTeamRules.isGeneric(spawn.team()))
        .filter(spawn -> CraftovSpawnTeamRules.accepts(spawn.team(), teamId)).toList();
    List<CraftovSpawnLocation> generic = spawns.stream()
        .filter(spawn -> CraftovSpawnTeamRules.isGeneric(spawn.team())).toList();
    List<CraftovSpawnLocation> candidates = !dedicated.isEmpty() ? dedicated
        : !generic.isEmpty() ? generic : List.copyOf(spawns);
    if (candidates.isEmpty()) return Optional.empty();
    List<CraftovSpawnLocation> unreserved = candidates.stream()
        .filter(spawn -> !reservedSpawnIds.contains(spawn.id())).toList();
    if (!unreserved.isEmpty()) candidates = unreserved;

    // Smooth weighted round-robin keeps the authored relative weights while
    // avoiding the clustering produced by hashing every ticket independently.
    // In particular, N equally weighted points are each used once before the
    // sequence wraps. Tickets are bounded by the room's player cap, so replaying
    // the tiny deterministic sequence keeps this helper stateless and makes a
    // world reload produce exactly the same assignment.
    double total = candidates.stream().mapToDouble(CraftovSpawnLocation::weight).sum();
    double[] current = new double[candidates.size()];
    int selected = 0;
    for (long round = 0; round <= ticket; round++) {
      selected = 0;
      for (int index = 0; index < candidates.size(); index++) {
        current[index] += candidates.get(index).weight();
        if (current[index] > current[selected]) selected = index;
      }
      current[selected] -= total;
    }
    return Optional.of(candidates.get(selected));
  }
}
