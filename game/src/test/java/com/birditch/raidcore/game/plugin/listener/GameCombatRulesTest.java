package com.birditch.raidcore.game.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidPlayer;
import com.birditch.raidcore.api.game.RaidTeam;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidGameState;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.api.game.RaidTeamRules;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GameCombatRulesTest {
  @Test
  void sameTeamIsDetectedOnlyForMembersWithMatchingTeam() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    FakeGame game = new FakeGame(first, second);

    assertTrue(RaidTeamRules.sameTeam(game, first, second));
    assertFalse(RaidTeamRules.sameTeam(game, first, UUID.randomUUID()));
  }

  private static final class FakeGame implements RaidGame {
    private final RaidPlayer first;
    private final RaidPlayer second;

    private FakeGame(UUID firstId, UUID secondId) {
      first = new RaidPlayer(firstId, "first");
      second = new RaidPlayer(secondId, "second");
      first.assignTeam(1);
      second.assignTeam(1);
    }

    public RaidGameId id() { return RaidGameId.of("raid-1"); }
    public RaidMapId mapId() { return RaidMapId.of("map"); }
    public RaidGameState state() { return RaidGameState.RUNNING; }
    public Collection<RaidPlayer> players() { return List.of(first, second); }
    public Collection<RaidTeam> teams() { return List.of(); }
    public List<ExtractionPoint> extractionPoints() { return List.of(); }
    public Instant createdAt() { return Instant.EPOCH; }
    public Optional<Instant> startedAt() { return Optional.of(Instant.EPOCH); }
    public Optional<RaidResult> result() { return Optional.empty(); }
    public int playerCount() { return 2; }
  }
}
