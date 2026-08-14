package com.birditch.escapefromcraftov.game.plugin.start;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.result.Result;
import com.birditch.escapefromcraftov.game.plugin.instance.InstanceWorldIndex;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/// Drives one independent automatic start countdown per ready WAITING room.
///
/// The one-second pass is main-thread confined. A room starts only while its
/// disposable world is ready and its population remains at or above the
/// configured minimum. Dropping below the threshold cancels that room only;
/// manually started or removed rooms are discarded without a false warning.
public final class CraftovStartCoordinator implements CraftovStartStatus, AutoCloseable {

  private static final Duration TICK = Duration.ofSeconds(1);

  private final CraftovGameService games;
  private final InstanceWorldIndex worlds;
  private final CraftovScheduler scheduler;
  private final CraftovStartNotifier notifier;
  private final Logger logger;
  private final int minimumPlayers;
  private final int countdownSeconds;
  private final Map<CraftovGameId, Integer> countdowns = new HashMap<>();
  private @Nullable ScheduledTask task;

  public CraftovStartCoordinator(CraftovGameService games, InstanceWorldIndex worlds,
      CraftovScheduler scheduler, CraftovStartNotifier notifier, Logger logger,
      int minimumPlayers, int countdownSeconds) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.worlds = Preconditions.checkNotNull(worlds, "worlds");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.notifier = Preconditions.checkNotNull(notifier, "notifier");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    Preconditions.checkArgument(minimumPlayers > 0, "minimumPlayers must be positive");
    Preconditions.checkArgument(countdownSeconds > 0, "countdownSeconds must be positive");
    this.minimumPlayers = minimumPlayers;
    this.countdownSeconds = countdownSeconds;
  }

  public void start() {
    Preconditions.checkState(task == null, "CraftovStartCoordinator already started");
    task = scheduler.runTimer(Duration.ZERO, TICK, this::tick);
  }

  @Override
  public OptionalInt remainingSeconds(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Integer value = countdowns.get(gameId);
    return value == null ? OptionalInt.empty() : OptionalInt.of(value);
  }

  @Override
  public void close() {
    if (task != null) {
      task.cancel();
      task = null;
    }
    countdowns.clear();
  }

  void tick() {
    Set<CraftovGameId> present = new HashSet<>();
    for (CraftovGame game : games.all()) {
      present.add(game.id());
      evaluate(game);
    }
    countdowns.keySet().removeIf(gameId -> !present.contains(gameId));
  }

  private void evaluate(CraftovGame game) {
    if (game.state() != CraftovGameState.WAITING) {
      countdowns.remove(game.id());
      return;
    }
    boolean eligible = worlds.isReady(game.id()) && game.playerCount() >= minimumPlayers;
    Integer remaining = countdowns.get(game.id());
    if (!eligible) {
      if (remaining != null) notifier.cancelled(game, minimumPlayers);
      countdowns.remove(game.id());
      return;
    }
    if (remaining == null) {
      remaining = countdownSeconds;
      countdowns.put(game.id(), remaining);
      notifier.countdown(game, remaining, minimumPlayers);
      return;
    }
    if (remaining > 1) {
      int next = remaining - 1;
      countdowns.put(game.id(), next);
      notifier.countdown(game, next, minimumPlayers);
      return;
    }
    countdowns.remove(game.id());
    switch (games.start(game.id())) {
      case Result.Success<CraftovGame>(CraftovGame started) -> notifier.started(started);
      case Result.Failure<CraftovGame>(String message) -> {
        logger.warning("自动开始对局 " + game.id().value() + " 失败:" + message);
        notifier.cancelled(game, minimumPlayers);
      }
    }
  }
}
