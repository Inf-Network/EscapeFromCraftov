package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnTeamRules;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Optional;

/// Pure deterministic team and weight selection for authored craftov spawns.
final class CraftovSpawnSelector {

  private CraftovSpawnSelector() {}

  static Optional<CraftovSpawnLocation> select(
      List<CraftovSpawnLocation> spawns, int teamId, long ticket) {
    Preconditions.checkNotNull(spawns, "spawns");
    List<CraftovSpawnLocation> dedicated = spawns.stream()
        .filter(spawn -> !CraftovSpawnTeamRules.isGeneric(spawn.team()))
        .filter(spawn -> CraftovSpawnTeamRules.accepts(spawn.team(), teamId)).toList();
    List<CraftovSpawnLocation> generic = spawns.stream()
        .filter(spawn -> CraftovSpawnTeamRules.isGeneric(spawn.team())).toList();
    List<CraftovSpawnLocation> candidates = !dedicated.isEmpty() ? dedicated
        : !generic.isEmpty() ? generic : List.copyOf(spawns);
    if (candidates.isEmpty()) return Optional.empty();

    double total = candidates.stream().mapToDouble(CraftovSpawnLocation::weight).sum();
    double target = unitInterval(mix(ticket)) * total;
    double cursor = 0;
    for (CraftovSpawnLocation candidate : candidates) {
      cursor += candidate.weight();
      if (target < cursor) return Optional.of(candidate);
    }
    return Optional.of(candidates.getLast());
  }

  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  private static double unitInterval(long value) {
    return (value >>> 11) * 0x1.0p-53;
  }
}
