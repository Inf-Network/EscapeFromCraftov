package com.birditch.escapefromcraftov.game.plugin.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LootClaimPolicyTest {

  private static final long NOW = 1_000;
  private static final long PROTECTION = 30_000;

  @Test
  void killerTeamIsFrozenAndOutsidersUnlockAfterThirtySeconds() {
    UUID killer = UUID.randomUUID();
    UUID teammate = UUID.randomUUID();
    UUID victim = UUID.randomUUID();
    FakeGame game = new FakeGame(killer, teammate, victim);

    LootClaim claim = LootClaimPolicy.forPlayerDeath(
        game, victim, killer, NOW, PROTECTION);

    assertEquals(Set.of(killer, teammate), claim.priorityPlayers());
    assertEquals(LootAccess.Kind.PRIORITY,
        LootClaimPolicy.access(game, killer, claim, NOW).kind());
    assertEquals(LootAccess.Kind.PRIORITY,
        LootClaimPolicy.access(game, teammate, claim, NOW + 1).kind());
    LootAccess protectedAccess = LootClaimPolicy.access(game, victim, claim, NOW + 1);
    assertEquals(LootAccess.Kind.PROTECTED, protectedAccess.kind());
    assertEquals(30, protectedAccess.secondsRemaining());
    assertEquals(LootAccess.Kind.PUBLIC,
        LootClaimPolicy.access(game, victim, claim, NOW + PROTECTION).kind());

    CraftovPlayer frozenTeammate = game.players().stream()
        .filter(player -> player.playerId().equals(teammate)).findFirst().orElseThrow();
    frozenTeammate.assignTeam(2);
    assertEquals(LootAccess.Kind.PRIORITY,
        LootClaimPolicy.access(game, teammate, claim, NOW + 1).kind());
    frozenTeammate.markExtracted();
    assertEquals(LootAccess.Kind.NOT_ACCESSIBLE,
        LootClaimPolicy.access(game, teammate, claim, NOW + PROTECTION).kind());
    assertEquals(LootAccess.Kind.NOT_ACCESSIBLE,
        LootClaimPolicy.access(game, UUID.randomUUID(), claim, NOW + PROTECTION).kind());
    LootClaim anotherRoom = new LootClaim(CraftovGameId.of("another-room"),
        Set.of(killer), NOW + PROTECTION);
    assertEquals(LootAccess.Kind.NOT_ACCESSIBLE,
        LootClaimPolicy.access(game, killer, anotherRoom, NOW).kind());
  }

  @Test
  void environmentalAndFriendlyDeathsAreImmediatelyPublic() {
    UUID killer = UUID.randomUUID();
    UUID teammate = UUID.randomUUID();
    UUID victim = UUID.randomUUID();
    FakeGame game = new FakeGame(killer, teammate, victim);

    LootClaim environmental = LootClaimPolicy.forPlayerDeath(
        game, victim, null, NOW, PROTECTION);
    LootClaim friendly = LootClaimPolicy.forPlayerDeath(
        game, teammate, killer, NOW, PROTECTION);
    LootClaim selfInflicted = LootClaimPolicy.forPlayerDeath(
        game, killer, killer, NOW, PROTECTION);

    assertEquals(Set.of(), environmental.priorityPlayers());
    assertEquals(Set.of(), friendly.priorityPlayers());
    assertEquals(Set.of(), selfInflicted.priorityPlayers());
    assertEquals(LootAccess.Kind.PUBLIC,
        LootClaimPolicy.access(game, victim, environmental, NOW).kind());
    assertEquals(LootAccess.Kind.PUBLIC,
        LootClaimPolicy.access(game, victim, friendly, NOW).kind());
  }

  private static final class FakeGame implements CraftovGame {
    private final List<CraftovPlayer> players;

    private FakeGame(UUID killer, UUID teammate, UUID victim) {
      CraftovPlayer first = player(killer, "killer", 1);
      CraftovPlayer second = player(teammate, "teammate", 1);
      CraftovPlayer third = player(victim, "victim", 2);
      players = List.of(first, second, third);
    }

    private static CraftovPlayer player(UUID id, String name, int teamId) {
      CraftovPlayer player = new CraftovPlayer(id, name);
      player.assignTeam(teamId);
      return player;
    }

    @Override public CraftovGameId id() { return CraftovGameId.of("loot-test"); }
    @Override public CraftovMapId mapId() { return CraftovMapId.of("test"); }
    @Override public CraftovGameState state() { return CraftovGameState.RUNNING; }
    @Override public Collection<CraftovPlayer> players() { return players; }
    @Override public Collection<CraftovTeam> teams() { return List.of(); }
    @Override public List<ExtractionPoint> extractionPoints() { return List.of(); }
    @Override public Instant createdAt() { return Instant.EPOCH; }
    @Override public Optional<Instant> startedAt() { return Optional.of(Instant.EPOCH); }
    @Override public Optional<CraftovResult> result() { return Optional.empty(); }
    @Override public int playerCount() { return players.size(); }
  }
}
