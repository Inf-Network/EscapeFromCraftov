package com.birditch.raidcore.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.event.EventBus;
import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.extraction.ExtractionPointId;
import com.birditch.raidcore.api.extraction.ExtractionState;
import com.birditch.raidcore.api.extraction.ExtractionType;
import com.birditch.raidcore.api.game.GameFailures;
import com.birditch.raidcore.api.game.RaidExtractionArea;
import com.birditch.raidcore.api.game.RaidGame;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.api.game.RaidSpawnLocation;
import com.birditch.raidcore.api.game.RaidAiZone;
import com.birditch.raidcore.api.game.RaidPoi;
import com.birditch.raidcore.api.testing.ManualScheduler;
import com.birditch.raidcore.api.testing.MutableTimeSource;
import com.birditch.raidcore.common.random.SeededRandomProvider;
import com.birditch.raidcore.core.config.RaidCoreConfig;
import com.birditch.raidcore.core.event.SimpleEventBus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class RaidMapRuntimeSnapshotTest {

  @Test
  void existingGameKeepsCapturedRevisionWhenProviderChanges() {
    MutableProvider provider = new MutableProvider(runtime(7, 10));
    RaidGameManager manager = manager(provider);
    RaidGame first = manager.create(RaidMapId.of("factory")).value().orElseThrow();

    provider.runtime = runtime(8, 300);
    RaidGame second = manager.create(RaidMapId.of("factory")).value().orElseThrow();

    assertEquals(7, first.mapRevision());
    assertEquals(10, first.spawnLocations().getFirst().x());
    assertEquals(8, second.mapRevision());
    assertEquals(300, second.spawnLocations().getFirst().x());
  }

  @Test
  void unavailableAuthoredMapFailsClosed() {
    RaidGameManager manager = manager(_ -> Optional.empty());

    var result = manager.create(RaidMapId.of("missing"));

    assertTrue(!result.isSuccess());
    assertEquals(GameFailures.MAP_NOT_AVAILABLE, result.error().orElseThrow());
  }

  @Test
  void creatingRaidRetainsAiZonesAndPoisInCapturedSnapshot() {
    RaidGameManager manager = manager(_ -> Optional.of(runtime(7, 10)));

    RaidGame game = manager.create(RaidMapId.of("factory")).value().orElseThrow();

    assertEquals(1, game.aiZones().size());
    assertEquals("scavengers", game.aiZones().getFirst().id());
    assertEquals(1, game.pois().size());
    assertEquals("central_factory", game.pois().getFirst().id());
  }

  private static RaidGameManager manager(RaidMapProvider provider) {
    Logger logger = Logger.getLogger(RaidMapRuntimeSnapshotTest.class.getName());
    EventBus eventBus = new SimpleEventBus(logger);
    return new RaidGameManager(new GameManagerDeps(new FakeSessionService(), eventBus,
        new ManualScheduler(), new SeededRandomProvider(4), new MutableTimeSource(0),
        new RaidCoreConfig.GameSettings(4, 2, 60, 4),
        new RaidCoreConfig.ExtractionSettings(10), provider, logger));
  }

  private static RaidMapRuntime runtime(long revision, int x) {
    ExtractionPointId pointId = ExtractionPointId.of("north_gate");
    return new RaidMapRuntime(revision,
        List.of(new RaidSpawnLocation("spawn", x, 64, 10, 0, 0)),
        List.of(),
        List.of(new ExtractionPoint(pointId, "North Gate", ExtractionType.NORMAL,
            Duration.ofSeconds(10), List.of(), ExtractionState.AVAILABLE)),
        List.of(new RaidExtractionArea(pointId, "North Gate", 0, 60, 450, 8, 70, 458)),
        List.of(new RaidAiZone("scavengers", "zombie", 2, 4, 60, 1.0, "scavengers",
            100, 60, 100, 120, 80, 120)),
        List.of(new RaidPoi("central_factory", "中央工厂", 3,
            180, 40, 180, 320, 90, 320)));
  }

  private static final class MutableProvider implements RaidMapProvider {
    private RaidMapRuntime runtime;

    private MutableProvider(RaidMapRuntime runtime) {
      this.runtime = runtime;
    }

    @Override
    public Optional<RaidMapRuntime> runtime(RaidMapId mapId) {
      return Optional.of(runtime);
    }
  }
}
