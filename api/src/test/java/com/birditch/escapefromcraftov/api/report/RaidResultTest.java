package com.birditch.escapefromcraftov.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RaidResultTest {

  @Test
  void deterministicIdMakesEveryTerminalPathShareOneIdempotencyKey() {
    CraftovGameId gameId = CraftovGameId.of("raid-42");
    UUID playerId = UUID.randomUUID();

    assertEquals(RaidResult.idFor(gameId, playerId), RaidResult.idFor(gameId, playerId));
  }

  @Test
  void rejectsAnEndBeforeTheRaidStart() {
    CraftovGameId gameId = CraftovGameId.of("raid-42");
    UUID playerId = UUID.randomUUID();

    assertThrows(IllegalArgumentException.class, () -> new RaidResult(
        RaidResult.idFor(gameId, playerId), gameId, playerId, CraftovMapId.of("carville"),
        RaidMode.NORMAL, RaidDifficulty.NORMAL, RaidOutcome.KILLED,
        Instant.ofEpochSecond(20), Instant.ofEpochSecond(10), 0, 0, 0, 0, 0, 0));
  }
}
