package com.birditch.escapefromcraftov.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionState;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.GameFailures;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.common.random.SeededRandomProvider;
import com.birditch.escapefromcraftov.core.config.EscapeFromCraftovConfig;
import com.birditch.escapefromcraftov.core.event.SimpleEventBus;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class CraftovMapRuntimeSnapshotTest {

  @Test
  void existingGameKeepsCapturedRevisionWhenProviderChanges() {
    MutableProvider provider = new MutableProvider(runtime(7, 10));
    CraftovGameManager manager = manager(provider);
    CraftovGame first = manager.create(CraftovMapId.of("factory")).value().orElseThrow();

    provider.runtime = runtime(8, 300);
    CraftovGame second = manager.create(CraftovMapId.of("factory")).value().orElseThrow();

    assertEquals(7, first.mapRevision());
    assertEquals(10, first.spawnLocations().getFirst().x());
    assertEquals(8, second.mapRevision());
    assertEquals(300, second.spawnLocations().getFirst().x());
  }

  @Test
  void unavailableAuthoredMapFailsClosed() {
    CraftovGameManager manager = manager(_ -> Optional.empty());

    var result = manager.create(CraftovMapId.of("missing"));

    assertTrue(!result.isSuccess());
    assertEquals(GameFailures.MAP_NOT_AVAILABLE, result.error().orElseThrow());
  }

  @Test
  void creatingCraftovRetainsAiZonesAndPoisInCapturedSnapshot() {
    CraftovGameManager manager = manager(_ -> Optional.of(runtime(7, 10)));

    CraftovGame game = manager.create(CraftovMapId.of("factory")).value().orElseThrow();

    assertEquals(1, game.aiZones().size());
    assertEquals("scavengers", game.aiZones().getFirst().id());
    assertEquals(1, game.pois().size());
    assertEquals("central_factory", game.pois().getFirst().id());
  }

  @Test
  void creatingCraftovCapturesMatchmakingModeAndDifficulty() {
    CraftovGameManager manager = manager(_ -> Optional.of(runtime(7, 10)));

    CraftovGame game = manager.create(CraftovMapId.of("factory"),
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD).value().orElseThrow();

    assertEquals(RaidMode.CONFIDENTIAL, game.mode());
    assertEquals(RaidDifficulty.HARD, game.difficulty());
    assertEquals(null, game.tierId());
    assertTrue(game.actionProfile().isEmpty());
  }

  @Test
  void canonicalActionTierMustExistAndIsFrozenIntoTheRoom() {
    CraftovGameManager manager = manager(_ -> Optional.of(runtime(7, 10)));
    RaidTierId tierId = RaidTierId.of("top_secret");

    CraftovGame game = manager.create(CraftovMapId.of("factory"),
        RaidMode.CONFIDENTIAL, RaidDifficulty.HARD, tierId).value().orElseThrow();

    assertEquals(tierId, game.tierId());
    assertEquals(tierId, game.actionProfile().orElseThrow().id());
    assertEquals(GameFailures.ACTION_TIER_NOT_AVAILABLE,
        manager.create(CraftovMapId.of("factory"), RaidMode.NORMAL, RaidDifficulty.NORMAL,
            RaidTierId.of("missing")).error().orElseThrow());
  }

  private static CraftovGameManager manager(CraftovMapProvider provider) {
    Logger logger = Logger.getLogger(CraftovMapRuntimeSnapshotTest.class.getName());
    EventBus eventBus = new SimpleEventBus(logger);
    return new CraftovGameManager(new GameManagerDeps(new FakeSessionService(), eventBus,
        new ManualScheduler(), new SeededRandomProvider(4), new MutableTimeSource(0),
        new EscapeFromCraftovConfig.GameSettings(4, 2, 60, 4),
        new EscapeFromCraftovConfig.ExtractionSettings(10), provider, logger));
  }

  private static CraftovMapRuntime runtime(long revision, int x) {
    ExtractionPointId pointId = ExtractionPointId.of("north_gate");
    return new CraftovMapRuntime(revision,
        List.of(new CraftovSpawnLocation("spawn", x, 64, 10, 0, 0)),
        List.of(),
        List.of(new ExtractionPoint(pointId, "North Gate", ExtractionType.NORMAL,
            Duration.ofSeconds(10), List.of(), ExtractionState.AVAILABLE)),
        List.of(new CraftovExtractionArea(pointId, "North Gate", 0, 60, 450, 8, 70, 458)),
        List.of(new CraftovAiZone("scavengers", "zombie", 2, 4, 60, 1.0, "scavengers",
            100, 60, 100, 120, 80, 120)),
        List.of(new CraftovPoi("central_factory", "中央工厂", 3,
            180, 40, 180, 320, 90, 320)));
  }

  private static final class MutableProvider implements CraftovMapProvider {
    private CraftovMapRuntime runtime;

    private MutableProvider(CraftovMapRuntime runtime) {
      this.runtime = runtime;
    }

    @Override
    public Optional<CraftovMapRuntime> runtime(CraftovMapId mapId) {
      return Optional.of(runtime);
    }
  }
}
