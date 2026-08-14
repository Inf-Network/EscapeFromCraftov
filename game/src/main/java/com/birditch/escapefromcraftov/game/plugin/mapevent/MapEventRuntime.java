package com.birditch.escapefromcraftov.game.plugin.mapevent;

import com.birditch.escapefromcraftov.api.event.Subscribe;
import com.birditch.escapefromcraftov.api.event.game.CraftovEndedEvent;
import com.birditch.escapefromcraftov.api.event.game.CraftovStartedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovGameService;
import com.birditch.escapefromcraftov.api.game.CraftovGameState;
import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.api.game.CraftovPlayer;
import com.birditch.escapefromcraftov.api.scheduler.CraftovScheduler;
import com.birditch.escapefromcraftov.api.scheduler.ScheduledTask;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import com.birditch.escapefromcraftov.game.plugin.extraction.EventCompletionLookup;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/** Schedules map events and exposes their authoritative per-game state. */
public final class MapEventRuntime implements AutoCloseable, EventCompletionLookup {

  public static final Duration UNLOADED_RETRY_DELAY = Duration.ofSeconds(5);

  private final CraftovGameService games;
  private final CraftovScheduler scheduler;
  private final TimeSource clock;
  private final Logger logger;
  private final MapEventTracker tracker;
  private final Map<EventKey, ScheduledTask> activationTasks = new HashMap<>();
  private final ArrayDeque<EventKey> ready = new ArrayDeque<>();
  private final Set<CraftovGameId> activeGames = new HashSet<>();
  private boolean closed;

  public MapEventRuntime(CraftovGameService games, CraftovScheduler scheduler, TimeSource clock,
      Logger logger) {
    this(games, scheduler, clock, logger, new MapEventTracker());
  }

  MapEventRuntime(CraftovGameService games, CraftovScheduler scheduler, TimeSource clock,
      Logger logger, MapEventTracker tracker) {
    this.games = Preconditions.checkNotNull(games, "games");
    this.scheduler = Preconditions.checkNotNull(scheduler, "scheduler");
    this.clock = Preconditions.checkNotNull(clock, "clock");
    this.logger = Preconditions.checkNotNull(logger, "logger");
    this.tracker = Preconditions.checkNotNull(tracker, "tracker");
  }

  @Subscribe
  public void onCraftovStarted(CraftovStartedEvent event) {
    start(event.gameId());
  }

  @Subscribe
  public void onCraftovEnded(CraftovEndedEvent event) {
    clear(event.gameId());
  }

  public Optional<MapEventTracker.Activation> claimNextActivation() {
    while (!ready.isEmpty()) {
      EventKey key = ready.removeFirst();
      Optional<MapEventTracker.Activation> activation = tracker.claim(key.gameId(), key.eventId());
      if (activation.isPresent()) return activation;
    }
    return Optional.empty();
  }

  /** Rate-limits an unloaded-chunk failure; exactly one task exists per event. */
  public boolean retryUnloaded(MapEventTracker.Activation activation) {
    if (closed || !tracker.retryUnloaded(activation)) return false;
    schedule(new EventKey(activation.gameId(), activation.event().id()), UNLOADED_RETRY_DELAY);
    return true;
  }

  public boolean fail(MapEventTracker.Activation activation) {
    return tracker.fail(activation);
  }

  public boolean spawned(MapEventTracker.Activation activation, UUID entityId) {
    return tracker.spawned(activation, entityId);
  }

  public boolean lostActive(CraftovGameId gameId, String eventId, UUID entityId) {
    if (closed || !tracker.lostActive(gameId, eventId, entityId)) return false;
    schedule(new EventKey(gameId, eventId), UNLOADED_RETRY_DELAY);
    return true;
  }

  public Optional<MapEventTracker.Completion> complete(CraftovGameId gameId, String eventId,
      UUID entityId, @Nullable UUID killerId) {
    CraftovGame game = games.find(gameId)
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null) return Optional.empty();
    Set<UUID> frozenTeam = frozenTeam(game, killerId);
    return tracker.complete(gameId, eventId, entityId, frozenTeam, clock.currentMillis());
  }

  public MapEventTracker.CacheAccess cacheAccess(CraftovGameId gameId, String lootPointId,
      UUID playerId) {
    MapEventTracker.CacheAccess access =
        tracker.cacheAccess(gameId, lootPointId, playerId, clock.currentMillis());
    if (access.kind() != MapEventTracker.CacheAccessKind.UNRESTRICTED) return access;
    CraftovGame game = games.find(gameId).orElse(null);
    boolean authoredCache = game != null && game.state() == CraftovGameState.RUNNING
        && game.mapEvents().stream().anyMatch(event -> event.enabled()
            && event.cacheLootPointId().equals(lootPointId));
    return authoredCache ? new MapEventTracker.CacheAccess(
        MapEventTracker.CacheAccessKind.LOCKED, 0) : access;
  }

  public Set<String> eventZoneIds(CraftovGameId gameId) {
    return tracker.eventZoneIds(gameId);
  }

  public Optional<CraftovMapEvent> event(CraftovGameId gameId, String eventId) {
    return tracker.event(gameId, eventId);
  }

  public boolean ownsActive(CraftovGameId gameId, String eventId, UUID entityId) {
    return tracker.ownsActive(gameId, eventId, entityId);
  }

  public MapEventTracker.State state(CraftovGameId gameId, String eventId) {
    return tracker.state(gameId, eventId);
  }

  /** Extraction gates are fail-closed for every state except exact per-game completion. */
  @Override
  public boolean completed(CraftovGameId gameId, String eventId) {
    return !closed && tracker.state(gameId, eventId) == MapEventTracker.State.COMPLETED;
  }

  public int attempts(CraftovGameId gameId, String eventId) {
    return tracker.attempts(gameId, eventId);
  }

  private void start(CraftovGameId gameId) {
    if (closed || !activeGames.add(gameId)) return;
    CraftovGame game = games.find(gameId)
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (game == null) {
      activeGames.remove(gameId);
      return;
    }
    // Keep enabled-but-invalid definitions in the state machine so runtime
    // validation reaches a terminal FAILED state instead of silently turning
    // their reserved AI zone back into an ordinary refill zone.
    List<CraftovMapEvent> events = game.mapEvents().stream()
        .filter(CraftovMapEvent::enabled).toList();
    tracker.start(gameId, events);
    if (!events.isEmpty()) {
      logger.info("地图事件运行时已排程: " + gameId.value() + "，事件 " + events.size() + " 个");
    }
    for (CraftovMapEvent event : events) {
      Duration delay = Duration.ofSeconds(event.startDelaySeconds());
      EventKey key = new EventKey(gameId, event.id());
      if (delay.isZero()) markReady(key);
      else schedule(key, delay);
    }
  }

  private void schedule(EventKey key, Duration delay) {
    if (closed || activationTasks.containsKey(key)) return;
    ScheduledTask task = scheduler.runLater(delay, () -> {
      activationTasks.remove(key);
      markReady(key);
    });
    activationTasks.put(key, task);
  }

  private void markReady(EventKey key) {
    CraftovGame game = games.find(key.gameId())
        .filter(candidate -> candidate.state() == CraftovGameState.RUNNING).orElse(null);
    if (!closed && game != null && tracker.ready(key.gameId(), key.eventId())) {
      ready.addLast(key);
    }
  }

  private void clear(CraftovGameId gameId) {
    activeGames.remove(gameId);
    activationTasks.entrySet().removeIf(entry -> {
      if (!entry.getKey().gameId().equals(gameId)) return false;
      entry.getValue().cancel();
      return true;
    });
    ready.removeIf(key -> key.gameId().equals(gameId));
    tracker.clear(gameId);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    for (ScheduledTask task : activationTasks.values()) task.cancel();
    activationTasks.clear();
    ready.clear();
    activeGames.clear();
    tracker.clear();
  }

  private static Set<UUID> frozenTeam(CraftovGame game, @Nullable UUID killerId) {
    CraftovPlayer killer = game.players().stream()
        .filter(player -> player.playerId().equals(killerId) && !player.extracted())
        .findFirst().orElse(null);
    if (killer == null) return Set.of();
    if (killer.teamId().isEmpty()) return Set.of(killerId);
    int teamId = killer.teamId().getAsInt();
    return game.players().stream()
        .filter(player -> player.teamId().isPresent() && player.teamId().getAsInt() == teamId)
        .map(CraftovPlayer::playerId).collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private record EventKey(CraftovGameId gameId, String eventId) {}
}
