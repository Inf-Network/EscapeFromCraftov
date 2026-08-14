package com.birditch.escapefromcraftov.game.plugin.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.inventory.LoadoutSlot;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
import com.birditch.escapefromcraftov.game.plugin.instance.FakeCraftovGame;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PendingJoinHandlerTest {

  private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final ServerId SERVER = ServerId.of("game-01");
  private static final CraftovGameId GAME = CraftovGameId.of("craftov-01");
  private static final CraftovMapId MAP = CraftovMapId.of("debug_zone");
  private static final GameServerConfig CONFIG = new GameServerConfig(
      SERVER, "craftov-backend-01", MAP, 2, 15, 1, "maps", false, "lobby", 2);

  @Test
  void acceptsOnlyAnAssignmentForThisPlayerAndBothLocalServerNames() {
    MatchAssignment assignment = assignment(PLAYER, SERVER, "craftov-backend-01");

    assertEquals(PendingJoinHandler.TargetValidation.VALID,
        PendingJoinHandler.validateTarget(PLAYER, assignment, CONFIG));
  }

  @Test
  void rejectsAnAssignmentStoredUnderTheWrongPlayer() {
    MatchAssignment assignment = assignment(
        UUID.fromString("00000000-0000-0000-0000-000000000002"), SERVER, "craftov-backend-01");

    assertEquals(PendingJoinHandler.TargetValidation.PLAYER_MISMATCH,
        PendingJoinHandler.validateTarget(PLAYER, assignment, CONFIG));
  }

  @Test
  void rejectsAnAssignmentForAnotherInternalGameServer() {
    MatchAssignment assignment = assignment(PLAYER, ServerId.of("game-02"), "craftov-backend-01");

    assertEquals(PendingJoinHandler.TargetValidation.SERVER_ID_MISMATCH,
        PendingJoinHandler.validateTarget(PLAYER, assignment, CONFIG));
  }

  @Test
  void rejectsAnAssignmentForAnotherProxyBackendEvenWhenServerIdMatches() {
    MatchAssignment assignment = assignment(PLAYER, SERVER, "craftov-backend-02");

    assertEquals(PendingJoinHandler.TargetValidation.PROXY_NAME_MISMATCH,
        PendingJoinHandler.validateTarget(PLAYER, assignment, CONFIG));
  }

  @Test
  void unwrapsNestedAsyncFailuresToTheirDomainCause() {
    var cause = new IllegalStateException("database unavailable");
    var wrapped = new java.util.concurrent.CompletionException(
        new java.util.concurrent.CompletionException(cause));

    assertSame(cause, PendingJoinHandler.unwrap(wrapped));
  }

  @Test
  void waitingEntryCanBeCancelledWithoutStartingAClaim() {
    var entry = new PendingJoinHandler.PendingEntry(
        assignment(PLAYER, SERVER, "craftov-backend-01"));

    assertTrue(entry.cancelBeforeClaim());
    assertEquals(PendingJoinHandler.ClaimOutcome.UNCLAIMED, entry.completion().join());
    assertFalse(entry.beginClaim());
  }

  @Test
  void startedEntryExposesOneCompletionForAQuitClaimBarrier() {
    var entry = new PendingJoinHandler.PendingEntry(
        assignment(PLAYER, SERVER, "craftov-backend-01"));

    assertTrue(entry.beginClaim());
    assertFalse(entry.beginClaim());
    assertFalse(entry.completion().isDone());
    entry.requestQuit();
    assertTrue(entry.quitRequested());

    entry.complete(PendingJoinHandler.ClaimOutcome.CLAIMED);
    assertEquals(PendingJoinHandler.ClaimOutcome.CLAIMED, entry.completion().join());
  }

  @Test
  void assignmentMustMatchTheRoomsExactNullableActionTier() {
    RaidTierId tierId = RaidTierId.of("top_secret");
    FakeCraftovGame canonical = new FakeCraftovGame(GAME, MAP,
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, tierId);
    MatchAssignment matching = new MatchAssignment(PLAYER, SERVER, "craftov-backend-01",
        GAME, MAP, PLAYER, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, tierId);
    MatchAssignment legacy = new MatchAssignment(PLAYER, SERVER, "craftov-backend-01",
        GAME, MAP, PLAYER, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, null);

    assertTrue(PendingJoinHandler.matchesPool(canonical, matching));
    assertFalse(PendingJoinHandler.matchesPool(canonical, legacy));
    assertFalse(PendingJoinHandler.matchesPool(
        new FakeCraftovGame(GAME, MAP, RaidMode.CONFIDENTIAL, RaidDifficulty.HARD), matching));
  }

  @Test
  void quickUseKeepsFirstPhysicalStackNamedAndCarriesDerivedStacksInBackpack() {
    PendingJoinHandler.NamedStackPlan<String> quick = PendingJoinHandler.planNamedStacks(
        LoadoutSlot.QUICK_USE_1, List.of("potion-1", "potion-2"));

    assertEquals("potion-1", quick.equipped());
    assertEquals(List.of("potion-2"), quick.backpack());
    assertThrows(IllegalStateException.class, () -> PendingJoinHandler.planNamedStacks(
        LoadoutSlot.MAIN_HAND, List.of("sword-1", "sword-2")));
  }

  private static MatchAssignment assignment(UUID playerId, ServerId serverId, String proxyName) {
    return new MatchAssignment(playerId, serverId, proxyName, GAME, MAP);
  }
}
