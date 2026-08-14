package com.birditch.escapefromcraftov.game.plugin.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.testing.ManualScheduler;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.instance.FakeCraftovGame;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CraftovStartCoordinatorTest {

  private static final CraftovMapId MAP = CraftovMapId.of("debug_zone");
  private StartableGames games;
  private InstanceWorldIndex worlds;
  private ManualScheduler scheduler;
  private RecordingNotifier notifier;
  private CraftovStartCoordinator coordinator;

  @BeforeEach
  void setUp() {
    games = new StartableGames();
    worlds = new InstanceWorldIndex();
    scheduler = new ManualScheduler();
    notifier = new RecordingNotifier();
    coordinator = new CraftovStartCoordinator(games, worlds, scheduler, notifier,
        Logger.getLogger(CraftovStartCoordinatorTest.class.getName()), 2, 3);
  }

  @Test
  void countsDownAndStartsAReadyRoomAtTheConfiguredThreshold() {
    FakeCraftovGame game = readyGame("game-1", 2);

    coordinator.start();
    scheduler.advanceBy(Duration.ZERO);
    assertEquals(3, coordinator.remainingSeconds(game.id()).orElseThrow());
    assertEquals(java.util.List.of(3), notifier.countdowns.get(game.id()));

    scheduler.advanceBy(Duration.ofSeconds(1));
    assertEquals(2, coordinator.remainingSeconds(game.id()).orElseThrow());
    scheduler.advanceBy(Duration.ofSeconds(1));
    assertEquals(1, coordinator.remainingSeconds(game.id()).orElseThrow());
    scheduler.advanceBy(Duration.ofSeconds(1));

    assertEquals(CraftovGameState.RUNNING, game.state());
    assertEquals(1, games.startCalls);
    assertEquals(1, notifier.started);
    assertTrue(coordinator.remainingSeconds(game.id()).isEmpty());
  }

  @Test
  void cancelsOnlyTheRoomThatDropsBelowTheMinimum() {
    FakeCraftovGame first = readyGame("game-1", 2);
    FakeCraftovGame second = readyGame("game-2", 2);
    coordinator.start();
    scheduler.advanceBy(Duration.ZERO);

    first.setPlayerCount(1);
    scheduler.advanceBy(Duration.ofSeconds(1));

    assertTrue(coordinator.remainingSeconds(first.id()).isEmpty());
    assertEquals(1, notifier.cancelled.getOrDefault(first.id(), 0));
    assertEquals(2, coordinator.remainingSeconds(second.id()).orElseThrow());
    assertEquals(0, notifier.cancelled.getOrDefault(second.id(), 0));
  }

  @Test
  void doesNotCountDownUntilTheDisposableWorldIsReady() {
    FakeCraftovGame game = games.add("game-1", 2);
    coordinator.start();
    scheduler.advanceBy(Duration.ZERO);

    assertTrue(coordinator.remainingSeconds(game.id()).isEmpty());
    assertFalse(notifier.countdowns.containsKey(game.id()));

    worlds.register(game.id(), "craftov_game-1");
    worlds.markReady(game.id());
    scheduler.advanceBy(Duration.ofSeconds(1));
    assertEquals(3, coordinator.remainingSeconds(game.id()).orElseThrow());
  }

  @Test
  void silentlyDropsTheCountdownAfterAnAdministratorStartsTheRoom() {
    FakeCraftovGame game = readyGame("game-1", 2);
    coordinator.start();
    scheduler.advanceBy(Duration.ZERO);
    game.setState(CraftovGameState.RUNNING);

    scheduler.advanceBy(Duration.ofSeconds(1));

    assertTrue(coordinator.remainingSeconds(game.id()).isEmpty());
    assertEquals(0, notifier.cancelled.getOrDefault(game.id(), 0));
  }

  @Test
  void closeCancelsTheTimerAndStartCannotRunTwice() {
    coordinator.start();
    assertThrows(IllegalStateException.class, coordinator::start);

    coordinator.close();

    assertEquals(0, scheduler.pendingCount());
  }

  private FakeCraftovGame readyGame(String id, int players) {
    FakeCraftovGame game = games.add(id, players);
    worlds.register(game.id(), "craftov_" + id);
    worlds.markReady(game.id());
    return game;
  }

  private static final class RecordingNotifier implements CraftovStartNotifier {
    private final Map<CraftovGameId, java.util.List<Integer>> countdowns = new LinkedHashMap<>();
    private final Map<CraftovGameId, Integer> cancelled = new LinkedHashMap<>();
    private int started;

    @Override
    public void countdown(CraftovGame game, int remainingSeconds, int minimumPlayers) {
      countdowns.computeIfAbsent(game.id(), ignored -> new java.util.ArrayList<>())
          .add(remainingSeconds);
    }

    @Override
    public void cancelled(CraftovGame game, int minimumPlayers) {
      cancelled.merge(game.id(), 1, Integer::sum);
    }

    @Override
    public void started(CraftovGame game) {
      started++;
    }
  }

  private static final class StartableGames implements CraftovGameService {
    private final Map<CraftovGameId, FakeCraftovGame> games = new LinkedHashMap<>();
    private int startCalls;

    private FakeCraftovGame add(String id, int players) {
      FakeCraftovGame game = new FakeCraftovGame(CraftovGameId.of(id), MAP);
      game.setPlayerCount(players);
      games.put(game.id(), game);
      return game;
    }

    @Override
    public Optional<CraftovGame> find(CraftovGameId id) {
      return Optional.ofNullable(games.get(id));
    }

    @Override
    public Collection<CraftovGame> all() {
      return java.util.List.copyOf(games.values());
    }

    @Override
    public Result<CraftovGame> start(CraftovGameId id) {
      startCalls++;
      FakeCraftovGame game = games.get(id);
      if (game == null) return Result.fail("missing");
      game.setState(CraftovGameState.RUNNING);
      return Result.ok(game);
    }

    @Override public Result<CraftovGame> create(CraftovMapId mapId) { throw unsupported(); }
    @Override public Result<CraftovGame> join(CraftovGameId gameId, UUID playerId) { throw unsupported(); }
    @Override public Result<CraftovGame> leave(UUID playerId) { throw unsupported(); }
    @Override public Result<CraftovGame> stop(CraftovGameId id) { throw unsupported(); }
    @Override public void destroyAll() { games.clear(); }
    @Override public int activeCount() { return games.size(); }

    private static UnsupportedOperationException unsupported() {
      return new UnsupportedOperationException();
    }
  }
}
