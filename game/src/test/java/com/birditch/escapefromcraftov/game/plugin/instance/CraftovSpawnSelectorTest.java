package com.birditch.escapefromcraftov.game.plugin.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovSpawnArea;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import java.util.List;
import org.junit.jupiter.api.Test;

class CraftovSpawnSelectorTest {

  @Test
  void dedicatedTeamSpawnTakesPriorityOverGenericSpawn() {
    List<CraftovSpawnLocation> spawns = List.of(
        spawn("generic", "players", 100),
        spawn("red", "red", 1),
        spawn("blue", "blue", 1));

    assertEquals("red", CraftovSpawnSelector.select(spawns, 1, 12).orElseThrow().id());
    assertEquals("blue", CraftovSpawnSelector.select(spawns, 2, 12).orElseThrow().id());
    assertEquals("generic", CraftovSpawnSelector.select(spawns, 3, 12).orElseThrow().id());
  }

  @Test
  void selectionHonorsRelativeWeights() {
    List<CraftovSpawnLocation> spawns = List.of(
        spawn("light", "players", 1), spawn("heavy", "players", 4));
    int heavy = 0;
    for (long ticket = 0; ticket < 10_000; ticket++) {
      if (CraftovSpawnSelector.select(spawns, 1, ticket).orElseThrow().id().equals("heavy")) heavy++;
    }
    assertTrue(heavy > 7_500 && heavy < 8_500, "heavy selections=" + heavy);
  }

  @Test
  void regionSamplingIsDeterministicBoundedAndStartsAtAnchor() {
    CraftovSpawnLocation spawn = new CraftovSpawnLocation("west", 5, 65, 5, 0, 0,
        "players", 1, new CraftovSpawnArea(0, 60, 0, 10, 70, 10));

    var first = CraftovSpawnResolver.candidates(spawn, 91, 24);
    var second = CraftovSpawnResolver.candidates(spawn, 91, 24);

    assertEquals(first, second);
    assertEquals(new CraftovSpawnResolver.Candidate(5, 65, 5), first.getFirst());
    assertTrue(first.size() <= CraftovSpawnResolver.MAXIMUM_BLOCK_CHECKS);
    assertTrue(first.stream().allMatch(candidate -> spawn.region().contains(
        candidate.x(), candidate.y(), candidate.z())));
  }

  private static CraftovSpawnLocation spawn(String id, String team, double weight) {
    return new CraftovSpawnLocation(id, 0, 64, 0, 0, 0, team, weight, null);
  }
}
