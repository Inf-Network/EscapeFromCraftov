package com.birditch.escapefromcraftov.game.plugin.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidReportService;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.game.plugin.GameplaySettings;
import com.birditch.escapefromcraftov.game.plugin.instance.FakeGameService;
import com.birditch.escapefromcraftov.item.SimpleItemRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RaidResultRuntimeTest {

  @Test
  void firstTerminalIntentWinsAsyncSettlementRaceButCommittedExtractionOverrides() {
    RaidResultRuntime runtime = runtime();
    var gameId = com.birditch.escapefromcraftov.api.game.CraftovGameId.of("race-1");
    UUID killed = UUID.randomUUID();
    UUID quit = UUID.randomUUID();

    assertEquals(RaidOutcome.KILLED,
        runtime.declareOutcome(gameId, killed, RaidOutcome.KILLED));
    assertEquals(RaidOutcome.KILLED,
        runtime.declareOutcome(gameId, killed, RaidOutcome.TIMED_OUT));
    assertEquals(RaidOutcome.QUIT,
        runtime.declareOutcome(gameId, quit, RaidOutcome.QUIT));
    assertEquals(RaidOutcome.EXTRACTED,
        runtime.declareOutcome(gameId, quit, RaidOutcome.EXTRACTED));
    assertEquals(RaidOutcome.EXTRACTED,
        runtime.outcomeOr(gameId, quit, RaidOutcome.TIMED_OUT));
  }

  private static RaidResultRuntime runtime() {
    RaidReportService reports = new RaidReportService() {
      @Override
      public CompletableFuture<RaidResult> record(RaidResult result) {
        return CompletableFuture.completedFuture(result);
      }

      @Override
      public CompletableFuture<List<RaidResult>> recent(UUID playerId, int limit) {
        return CompletableFuture.completedFuture(List.of());
      }
    };
    RaidResultCollector collector = new RaidResultCollector(reports,
        new SimpleItemRegistry(), new MutableTimeSource(0));
    GameplaySettings settings = new GameplaySettings(false, 10, true, 10,
        54, 300, 50, 100);
    return new RaidResultRuntime(new FakeGameService(), collector,
        new ManualScheduler(), settings, Logger.getAnonymousLogger());
  }
}
