package com.birditch.escapefromcraftov.game.plugin.mapevent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.CraftovMapEventType;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.game.CraftovResult;
import com.birditch.escapefromcraftov.api.game.CraftovTeam;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.api.testing.MutableTimeSource;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class MapEventRuntimeTest {

  private static final CraftovGameId GAME_ID = CraftovGameId.of("event-game");
  private static final CraftovMapEvent EVENT = new CraftovMapEvent(
      "boss-1", "仓库守卫", CraftovMapEventType.HIGH_VALUE_TARGET,
      "ai-1", "loot-004", 0, 2.5, 3, 30, true);

  @Test
  void unloadedRetriesAreRateLimitedAndLaterSpawnOnlyOnce() {
    EventGame game = new EventGame(List.of(), List.of(EVENT));
    ManualScheduler scheduler = new ManualScheduler();
    MapEventRuntime runtime = runtime(game, scheduler, new MutableTimeSource(0));
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));

    MapEventTracker.Activation first = runtime.claimNextActivation().orElseThrow();
    assertTrue(runtime.retryUnloaded(first));
    assertFalse(runtime.retryUnloaded(first));
    assertEquals(1, scheduler.pendingCount(), "one event owns at most one retry task");

    scheduler.advanceBy(MapEventRuntime.UNLOADED_RETRY_DELAY);
    MapEventTracker.Activation second = runtime.claimNextActivation().orElseThrow();
    assertTrue(runtime.retryUnloaded(second));
    scheduler.advanceBy(MapEventRuntime.UNLOADED_RETRY_DELAY);
    MapEventTracker.Activation loaded = runtime.claimNextActivation().orElseThrow();
    UUID boss = UUID.randomUUID();
    assertTrue(runtime.spawned(loaded, boss));

    scheduler.advanceBy(Duration.ofMinutes(10));
    assertTrue(runtime.claimNextActivation().isEmpty());
    assertTrue(runtime.ownsActive(GAME_ID, EVENT.id(), boss));
    assertEquals(3, runtime.attempts(GAME_ID, EVENT.id()));
  }

  @Test
  void completionIsFirstWinsAndFreezesTheKillerTeamUntilExpiry() {
    UUID killer = UUID.randomUUID();
    UUID teammate = UUID.randomUUID();
    UUID outsider = UUID.randomUUID();
    CraftovPlayer first = player(killer, 1);
    CraftovPlayer second = player(teammate, 1);
    CraftovPlayer third = player(outsider, 2);
    MutableTimeSource time = new MutableTimeSource(1_000);
    MapEventRuntime runtime = runtime(new EventGame(List.of(first, second, third), List.of(EVENT)),
        new ManualScheduler(), time);
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    MapEventTracker.Activation activation = runtime.claimNextActivation().orElseThrow();
    UUID boss = UUID.randomUUID();
    assertTrue(runtime.spawned(activation, boss));

    assertTrue(runtime.complete(GAME_ID, EVENT.id(), boss, killer).isPresent());
    assertTrue(runtime.complete(GAME_ID, EVENT.id(), boss, outsider).isEmpty());
    assertEquals(MapEventTracker.CacheAccessKind.PRIORITY,
        runtime.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), teammate).kind());
    assertEquals(MapEventTracker.CacheAccessKind.PROTECTED,
        runtime.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), outsider).kind());

    time.advance(Duration.ofSeconds(EVENT.protectionSeconds()));
    assertEquals(MapEventTracker.CacheAccessKind.PUBLIC,
        runtime.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), outsider).kind());
  }

  @Test
  void authoredCacheIsFailClosedWhenTrackerWasNeverStarted() {
    EventGame game = new EventGame(List.of(), List.of(EVENT));
    MapEventRuntime runtime = runtime(game, new ManualScheduler(), new MutableTimeSource(0));

    assertEquals(MapEventTracker.CacheAccessKind.LOCKED,
        runtime.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), UUID.randomUUID()).kind());
    assertEquals(MapEventTracker.CacheAccessKind.UNRESTRICTED,
        runtime.cacheAccess(GAME_ID, "ordinary-cache", UUID.randomUUID()).kind());
  }

  @Test
  void lostActiveBossReleasesIdentityBeforeOneBoundedRetry() {
    MapEventRuntime runtime = runtime(new EventGame(List.of(), List.of(EVENT)),
        new ManualScheduler(), new MutableTimeSource(0));
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    MapEventTracker.Activation activation = runtime.claimNextActivation().orElseThrow();
    UUID oldBoss = UUID.randomUUID();
    assertTrue(runtime.spawned(activation, oldBoss));

    assertTrue(runtime.lostActive(GAME_ID, EVENT.id(), oldBoss));
    assertFalse(runtime.ownsActive(GAME_ID, EVENT.id(), oldBoss));
    assertFalse(runtime.lostActive(GAME_ID, EVENT.id(), oldBoss));
  }

  @Test
  void unloadedRetryHasAHardAttemptCeiling() {
    ManualScheduler scheduler = new ManualScheduler();
    MapEventRuntime runtime = runtime(new EventGame(List.of(), List.of(EVENT)), scheduler,
        new MutableTimeSource(0));
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    for (int attempt = 1; attempt <= MapEventTracker.MAXIMUM_SPAWN_ATTEMPTS; attempt++) {
      MapEventTracker.Activation activation = runtime.claimNextActivation().orElseThrow();
      assertEquals(attempt, activation.attempt());
      if (attempt < MapEventTracker.MAXIMUM_SPAWN_ATTEMPTS) {
        assertTrue(runtime.retryUnloaded(activation));
        assertEquals(1, scheduler.pendingCount());
        scheduler.advanceBy(MapEventRuntime.UNLOADED_RETRY_DELAY);
      } else {
        assertFalse(runtime.retryUnloaded(activation));
      }
    }
    assertEquals(MapEventTracker.State.FAILED, runtime.state(GAME_ID, EVENT.id()));
    assertEquals(0, scheduler.pendingCount());
    assertTrue(runtime.claimNextActivation().isEmpty());
  }

  @Test
  void sharedCacheStaysLockedUntilEveryReferencingEventCompletes() {
    CraftovMapEvent second = new CraftovMapEvent(
        "boss-2", "第二目标", CraftovMapEventType.HIGH_VALUE_TARGET,
        "ai-2", EVENT.cacheLootPointId(), 0, 2, 2, 30, true);
    MapEventTracker tracker = new MapEventTracker();
    tracker.start(GAME_ID, List.of(EVENT, second));
    UUID player = UUID.randomUUID();
    UUID firstBoss = UUID.randomUUID();
    UUID secondBoss = UUID.randomUUID();

    assertTrue(tracker.ready(GAME_ID, EVENT.id()));
    MapEventTracker.Activation first = tracker.claim(GAME_ID, EVENT.id()).orElseThrow();
    assertTrue(tracker.spawned(first, firstBoss));
    assertTrue(tracker.complete(GAME_ID, EVENT.id(), firstBoss, Set.of(player), 0).isPresent());
    assertEquals(MapEventTracker.CacheAccessKind.LOCKED,
        tracker.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), player, 0).kind());

    assertTrue(tracker.ready(GAME_ID, second.id()));
    MapEventTracker.Activation later = tracker.claim(GAME_ID, second.id()).orElseThrow();
    assertTrue(tracker.spawned(later, secondBoss));
    assertTrue(tracker.complete(GAME_ID, second.id(), secondBoss, Set.of(player), 0).isPresent());
    assertEquals(MapEventTracker.CacheAccessKind.PRIORITY,
        tracker.cacheAccess(GAME_ID, EVENT.cacheLootPointId(), player, 0).kind());
  }

  @Test
  void completionLookupIsIsolatedByGameAndClearsFailClosed() {
    CraftovGameId otherId = CraftovGameId.of("event-game-other");
    EventGame first = new EventGame(GAME_ID, List.of(), List.of(EVENT));
    EventGame second = new EventGame(otherId, List.of(), List.of(EVENT));
    MapEventRuntime runtime = new MapEventRuntime(new MultiGameService(List.of(first, second)),
        new ManualScheduler(), new MutableTimeSource(0),
        Logger.getLogger(MapEventRuntimeTest.class.getName()));
    runtime.onCraftovStarted(new CraftovStartedEvent(GAME_ID));
    runtime.onCraftovStarted(new CraftovStartedEvent(otherId));
    MapEventTracker.Activation firstActivation = runtime.claimNextActivation().orElseThrow();
    MapEventTracker.Activation secondActivation = runtime.claimNextActivation().orElseThrow();
    UUID firstBoss = UUID.randomUUID();
    UUID secondBoss = UUID.randomUUID();
    assertTrue(runtime.spawned(firstActivation, firstBoss));
    assertTrue(runtime.spawned(secondActivation, secondBoss));

    assertFalse(runtime.completed(GAME_ID, EVENT.id()));
    assertFalse(runtime.completed(otherId, EVENT.id()));
    assertTrue(runtime.complete(GAME_ID, EVENT.id(), firstBoss, null).isPresent());
    assertTrue(runtime.completed(GAME_ID, EVENT.id()));
    assertFalse(runtime.completed(otherId, EVENT.id()));
    assertTrue(runtime.complete(otherId, EVENT.id(), secondBoss, null).isPresent());
    assertTrue(runtime.completed(otherId, EVENT.id()));

    runtime.onCraftovEnded(new CraftovEndedEvent(GAME_ID,
        new CraftovResult(GAME_ID, Instant.EPOCH, Set.of(), Set.of())));
    assertFalse(runtime.completed(GAME_ID, EVENT.id()));
    assertTrue(runtime.completed(otherId, EVENT.id()));
    runtime.close();
    assertFalse(runtime.completed(otherId, EVENT.id()));
  }

  private static MapEventRuntime runtime(EventGame game, ManualScheduler scheduler,
      MutableTimeSource time) {
    return new MapEventRuntime(new SingleGameService(game), scheduler, time,
        Logger.getLogger(MapEventRuntimeTest.class.getName()));
  }

  private static CraftovPlayer player(UUID id, int team) {
    CraftovPlayer player = new CraftovPlayer(id, id.toString());
    player.assignTeam(team);
    return player;
  }

  private record EventGame(CraftovGameId gameId, List<CraftovPlayer> players,
                           List<CraftovMapEvent> mapEvents) implements CraftovGame {
    private EventGame(List<CraftovPlayer> players, List<CraftovMapEvent> mapEvents) {
      this(GAME_ID, players, mapEvents);
    }
    @Override public CraftovGameId id() { return gameId; }
    @Override public CraftovMapId mapId() { return CraftovMapId.of("test-flat"); }
    @Override public CraftovGameState state() { return CraftovGameState.RUNNING; }
    @Override public Collection<CraftovTeam> teams() { return List.of(); }
    @Override public List<ExtractionPoint> extractionPoints() { return List.of(); }
    @Override public Instant createdAt() { return Instant.EPOCH; }
    @Override public Optional<Instant> startedAt() { return Optional.of(Instant.EPOCH); }
    @Override public Optional<CraftovResult> result() { return Optional.empty(); }
    @Override public int playerCount() { return players.size(); }
  }

  private record SingleGameService(EventGame game) implements CraftovGameService {
    @Override public Result<CraftovGame> create(CraftovMapId mapId) {
      return Result.fail("unsupported");
    }
    @Override public Optional<CraftovGame> find(CraftovGameId id) {
      return game.id().equals(id) ? Optional.of(game) : Optional.empty();
    }
    @Override public Collection<CraftovGame> all() { return List.of(game); }
    @Override public Result<CraftovGame> join(CraftovGameId id, UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> leave(UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> start(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> stop(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public void destroyAll() {}
    @Override public int activeCount() { return 1; }
  }

  private record MultiGameService(List<EventGame> games) implements CraftovGameService {
    @Override public Result<CraftovGame> create(CraftovMapId mapId) {
      return Result.fail("unsupported");
    }
    @Override public Optional<CraftovGame> find(CraftovGameId id) {
      return games.stream().filter(game -> game.id().equals(id))
          .map(CraftovGame.class::cast).findFirst();
    }
    @Override public Collection<CraftovGame> all() { return List.copyOf(games); }
    @Override public Result<CraftovGame> join(CraftovGameId id, UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> leave(UUID playerId) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> start(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public Result<CraftovGame> stop(CraftovGameId id) {
      return Result.fail("unsupported");
    }
    @Override public void destroyAll() {}
    @Override public int activeCount() { return games.size(); }
  }
}
