package com.birditch.escapefromcraftov.game.plugin.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.network.MatchAssignment;
import com.birditch.escapefromcraftov.api.network.ServerId;
import com.birditch.escapefromcraftov.game.plugin.GameServerConfig;
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

  private static MatchAssignment assignment(UUID playerId, ServerId serverId, String proxyName) {
    return new MatchAssignment(playerId, serverId, proxyName, GAME, MAP);
  }
}
