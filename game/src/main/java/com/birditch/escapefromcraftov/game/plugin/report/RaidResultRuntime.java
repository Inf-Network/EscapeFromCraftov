package com.birditch.escapefromcraftov.game.plugin.report;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.event.game.PlayerJoinedCraftovEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.game.plugin.GameplaySettings;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Main-thread lifecycle and retry coordinator around the durable raid report
 * collector. Inventory settlement never waits on this secondary record, while
 * a transient report write failure keeps retrying the same frozen result.
 */
public final class RaidResultRuntime implements AutoCloseable {

  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration COMPLETED_GAME_RETENTION = Duration.ofHours(1);

  private final CraftovGameService games;
  private final RaidResultCollector collector;
  private final CraftovScheduler scheduler;
  private final GameplaySettings settings;
  private final Logger logger;
  private final Map<Key, PendingReport> pending = new HashMap<>();
  private final Map<Key, RaidOutcome> terminalIntents = new HashMap<>();
  private final Map<CraftovGameId, ScheduledTask> cleanupTasks = new HashMap<>();
  private boolean closed;

  public RaidResultRuntime(CraftovGameService games, RaidResultCollector collector,
      CraftovScheduler scheduler, GameplaySettings settings, Logger logger) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.collector = Preconditions.checkNotNull(collector, "collector");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.settings = Preconditions.checkNotNull(settings, "settings");
    this.logger = Preconditions.checkNotNull(logger, "logger");
  }

  @Subscribe
  public void onPlayerJoined(PlayerJoinedCraftovEvent event) {
    games.find(event.gameId()).ifPresent(game -> collector.track(game, event.playerId()));
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    games.find(event.gameId()).ifPresent(collector::markStarted);
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    scheduleCleanup(event.gameId(), COMPLETED_GAME_RETENTION);
  }

  public void recordKill(CraftovGameId gameId, UUID playerId) {
    collector.recordKill(gameId, playerId);
  }

  public int kills(CraftovGameId gameId, UUID playerId) {
    return collector.kills(gameId, playerId);
  }

  /**
   * Claims the semantic terminal path before its durable inventory transaction starts.
   * The first main-thread event wins, so a later timeout callback cannot relabel a death
   * or quit merely because its database future completed sooner.
   */
  public RaidOutcome declareOutcome(CraftovGameId gameId, UUID playerId,
      RaidOutcome outcome) {
    Key key = new Key(Preconditions.checkNotNull(gameId, "gameId"),
        Preconditions.checkNotNull(playerId, "playerId"));
    Preconditions.checkNotNull(outcome, "outcome");
    if (outcome == RaidOutcome.EXTRACTED) {
      // A committed extraction is the authoritative inventory outcome.
      terminalIntents.put(key, outcome);
      return outcome;
    }
    return terminalIntents.computeIfAbsent(key, _ -> outcome);
  }

  public RaidOutcome outcomeOr(CraftovGameId gameId, UUID playerId,
      RaidOutcome fallback) {
    return terminalIntents.getOrDefault(new Key(gameId, playerId), fallback);
  }

  /** Starts or joins the one retrying report write for this player and raid. */
  public CompletableFuture<RaidResult> complete(CraftovGameId gameId, UUID playerId,
      RaidOutcome outcome, Collection<ItemInstance> retainedItems) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(outcome, "outcome");
    Preconditions.checkNotNull(retainedItems, "retainedItems");
    Key key = new Key(gameId, playerId);
    PendingReport existing = pending.get(key);
    if (existing != null) return existing.completion;
    RaidOutcome effective = declareOutcome(gameId, playerId, outcome);
    long experience = experience(effective, collector.kills(gameId, playerId));
    RaidResult frozen = collector.freeze(gameId, playerId, effective,
        List.copyOf(retainedItems), experience);
    PendingReport created = new PendingReport(frozen, new CompletableFuture<>());
    pending.put(key, created);
    Player online = Bukkit.getPlayer(playerId);
    if (online != null && online.isOnline()) RaidResultPresenter.show(online, frozen);
    attempt(key, created, 0);
    return created.completion;
  }

  private void attempt(Key key, PendingReport expected, int attempt) {
    if (closed || pending.get(key) != expected) return;
    CompletableFuture<RaidResult> write;
    try {
      write = collector.record(expected.result);
    } catch (RuntimeException failure) {
      handleFailure(key, expected, attempt, failure);
      return;
    }
    write.whenComplete((result, failure) -> {
      // Complete the caller-facing future on the IO callback so orderly server
      // shutdown can wait for the durable row without deadlocking the main
      // thread. Bukkit presentation and mutable maps still hop back to sync.
      if (failure == null && result != null) expected.completion.complete(result);
      scheduler.runSync(() -> {
        if (pending.get(key) != expected) return;
        if (failure != null || result == null) {
          handleFailure(key, expected, attempt, failure == null
              ? new IllegalStateException("Raid report store returned null") : failure);
          return;
        }
        pending.remove(key, expected);
      });
    });
  }

  private void handleFailure(Key key, PendingReport expected, int attempt, Throwable failure) {
    if (closed || pending.get(key) != expected) return;
    logger.log(Level.WARNING,
        "行动战报写入失败，将在 5 秒后重试 (第 " + (attempt + 1) + " 次):"
            + key.gameId().value() + "/" + key.playerId(), failure);
    expected.retry = scheduler.runLater(RETRY_DELAY, () -> {
      expected.retry = null;
      attempt(key, expected, attempt + 1);
    });
  }

  private long experience(RaidOutcome outcome, int kills) {
    long killExperience = (long) settings.killExperience() * Math.max(0, kills);
    long terminal = switch (outcome) {
      case EXTRACTED -> settings.extractionExperience();
      case KILLED -> settings.deathExperience();
      case QUIT, TIMED_OUT, FAILED, ABORTED -> 0;
    };
    return Math.addExact(killExperience, terminal);
  }

  private void scheduleCleanup(CraftovGameId gameId, Duration delay) {
    ScheduledTask previous = cleanupTasks.remove(gameId);
    if (previous != null) previous.cancel();
    cleanupTasks.put(gameId, scheduler.runLater(delay, () -> {
      cleanupTasks.remove(gameId);
      if (pending.keySet().stream().anyMatch(key -> key.gameId().equals(gameId))) {
        scheduleCleanup(gameId, RETRY_DELAY);
        return;
      }
      collector.discard(gameId);
      terminalIntents.keySet().removeIf(key -> key.gameId().equals(gameId));
    }));
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    cleanupTasks.values().forEach(ScheduledTask::cancel);
    cleanupTasks.clear();
    for (PendingReport report : pending.values()) {
      if (report.retry != null) report.retry.cancel();
      report.completion.completeExceptionally(
          new IllegalStateException("Raid report runtime is closing"));
    }
    pending.clear();
    terminalIntents.clear();
  }

  private record Key(CraftovGameId gameId, UUID playerId) {}

  private static final class PendingReport {
    private final RaidResult result;
    private final CompletableFuture<RaidResult> completion;
    private ScheduledTask retry;

    private PendingReport(RaidResult result, CompletableFuture<RaidResult> completion) {
      this.result = result;
      this.completion = completion;
    }
  }
}
