package com.birditch.raidcore.game.plugin.instance;

import com.birditch.raidcore.api.game.RaidSpawnLocation;
import com.birditch.raidcore.api.game.RaidSpawnTeamRules;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;
import java.util.Optional;

/// Pure deterministic team and weight selection for authored raid spawns.
final class RaidSpawnSelector {

  private RaidSpawnSelector() {}

  static Optional<RaidSpawnLocation> select(
      List<RaidSpawnLocation> spawns, int teamId, long ticket) {
    Preconditions.checkNotNull(spawns, "spawns");
    List<RaidSpawnLocation> dedicated = spawns.stream()
        .filter(spawn -> !RaidSpawnTeamRules.isGeneric(spawn.team()))
        .filter(spawn -> RaidSpawnTeamRules.accepts(spawn.team(), teamId)).toList();
    List<RaidSpawnLocation> generic = spawns.stream()
        .filter(spawn -> RaidSpawnTeamRules.isGeneric(spawn.team())).toList();
    List<RaidSpawnLocation> candidates = !dedicated.isEmpty() ? dedicated
        : !generic.isEmpty() ? generic : List.copyOf(spawns);
    if (candidates.isEmpty()) return Optional.empty();

    double total = candidates.stream().mapToDouble(RaidSpawnLocation::weight).sum();
    double target = unitInterval(mix(ticket)) * total;
    double cursor = 0;
    for (RaidSpawnLocation candidate : candidates) {
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
