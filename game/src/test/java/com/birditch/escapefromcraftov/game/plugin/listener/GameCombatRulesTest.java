package com.birditch.escapefromcraftov.game.plugin.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeamRules;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
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

    assertTrue(CraftovTeamRules.sameTeam(game, first, second));
    assertFalse(CraftovTeamRules.sameTeam(game, first, UUID.randomUUID()));
  }

  private static final class FakeGame implements CraftovGame {
    private final CraftovPlayer first;
    private final CraftovPlayer second;

    private FakeGame(UUID firstId, UUID secondId) {
      first = new CraftovPlayer(firstId, "first");
      second = new CraftovPlayer(secondId, "second");
      first.assignTeam(1);
      second.assignTeam(1);
    }

    public CraftovGameId id() { return CraftovGameId.of("craftov-1"); }
    public CraftovMapId mapId() { return CraftovMapId.of("map"); }
    public CraftovGameState state() { return CraftovGameState.RUNNING; }
    public Collection<CraftovPlayer> players() { return List.of(first, second); }
    public Collection<CraftovTeam> teams() { return List.of(); }
    public List<ExtractionPoint> extractionPoints() { return List.of(); }
    public Instant createdAt() { return Instant.EPOCH; }
    public Optional<Instant> startedAt() { return Optional.of(Instant.EPOCH); }
    public Optional<CraftovResult> result() { return Optional.empty(); }
    public int playerCount() { return 2; }
  }
}
