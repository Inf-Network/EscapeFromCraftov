package com.birditch.escapefromcraftov.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.core.database.entity.RaidResultEntity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernateRaidReportServiceTest {

  @Test
  void entityRoundTripKeepsEveryBattleReportField() {
    CraftovGameId gameId = CraftovGameId.of("raid-7");
    UUID playerId = UUID.randomUUID();
    RaidResult expected = new RaidResult(RaidResult.idFor(gameId, playerId), gameId, playerId,
        CraftovMapId.of("test_flat"), RaidMode.CONFIDENTIAL, RaidDifficulty.HARD,
        RaidOutcome.EXTRACTED, Instant.ofEpochSecond(100), Instant.ofEpochSecond(400),
        3, 14, 82_000, 1_250, 82_000);
    RaidResultEntity entity = new RaidResultEntity(expected.resultId(), gameId.value(), playerId,
        expected.mapId().value(), expected.mode(), expected.difficulty(), expected.outcome(),
        expected.startedAt(), expected.endedAt(), expected.kills(), expected.extractedItemCount(),
        expected.extractedValue(), expected.experience(), expected.earnings());

    assertEquals(expected, HibernateRaidReportService.fromEntity(entity));
  }
}
