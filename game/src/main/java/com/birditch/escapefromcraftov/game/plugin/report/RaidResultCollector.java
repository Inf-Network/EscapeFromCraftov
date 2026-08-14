package com.birditch.escapefromcraftov.game.plugin.report;

import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.item.ItemInstance;
import com.birditch.escapefromcraftov.api.item.ItemRegistry;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import com.birditch.escapefromcraftov.api.report.RaidReportService;
import com.birditch.escapefromcraftov.api.report.RaidResult;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Main-thread raid telemetry accumulator. Terminal inventory paths call
 * {@link #complete} only after their durable settlement succeeds. The deterministic
 * result id and frozen terminal snapshot make retries safe across extraction,
 * death, quit and timeout paths.
 */
public final class RaidResultCollector {

  private final RaidReportService reports;
  private final ItemRegistry items;
  private final TimeSource time;
  private final Map<Key, MutableRaid> active = new HashMap<>();
  private final Map<Key, RaidResult> terminal = new HashMap<>();

  public RaidResultCollector(RaidReportService reports, ItemRegistry items, TimeSource time) {
    this.reports = Preconditions.checkNotNull(reports, "reports");
    this.items = Preconditions.checkNotNull(items, "items");
    this.time = Preconditions.checkNotNull(time, "time");
  }

  /** Captures immutable matchmaking metadata for one participant. Safe to repeat. */
  public void track(CraftovGame game, UUID playerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    Key key = new Key(game.id(), playerId);
    if (terminal.containsKey(key)) return;
    active.computeIfAbsent(key, _ ->
        new MutableRaid(game.mapId(), game.mode(), game.difficulty(), time.now()));
  }

  /** Uses the actual action start rather than lobby/waiting time for duration. */
  public void markStarted(CraftovGame game) {
    Preconditions.checkNotNull(game, "game");
    Instant startedAt = time.now();
    game.players().forEach(player -> {
      Key key = new Key(game.id(), player.playerId());
      if (terminal.containsKey(key)) return;
      MutableRaid raid = active.computeIfAbsent(key, _ ->
          new MutableRaid(game.mapId(), game.mode(), game.difficulty(), startedAt));
      raid.startedAt = startedAt;
    });
  }

  /** Adds a confirmed hostile-player kill. Late events after completion are ignored. */
  public void recordKill(CraftovGameId gameId, UUID playerId) {
    Key key = new Key(Preconditions.checkNotNull(gameId, "gameId"),
        Preconditions.checkNotNull(playerId, "playerId"));
    MutableRaid raid = active.get(key);
    if (raid != null && !terminal.containsKey(key)) raid.kills++;
  }

  /**
   * Freezes and persists one terminal report. Extracted item count uses physical
   * quantity, and value/earnings use the authoritative item registry base price.
   * Repeated calls persist the exact same frozen object.
   */
  public CompletableFuture<RaidResult> complete(CraftovGame game, UUID playerId,
      RaidOutcome outcome, Collection<ItemInstance> extractedItems, long experience) {
    track(game, playerId);
    return complete(game.id(), playerId, outcome, extractedItems, experience);
  }

  public CompletableFuture<RaidResult> complete(CraftovGameId gameId, UUID playerId,
      RaidOutcome outcome, Collection<ItemInstance> extractedItems, long experience) {
    return record(freeze(gameId, playerId, outcome, extractedItems, experience));
  }

  /** Freezes one terminal snapshot synchronously so it can be shown before proxy transfer. */
  public RaidResult freeze(CraftovGameId gameId, UUID playerId,
      RaidOutcome outcome, Collection<ItemInstance> extractedItems, long experience) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(outcome, "outcome");
    Preconditions.checkNotNull(extractedItems, "extractedItems");
    Preconditions.checkArgument(experience >= 0, "experience must not be negative");
    Key key = new Key(gameId, playerId);
    RaidResult result = terminal.get(key);
    if (result == null) {
      MutableRaid raid = active.get(key);
      if (raid == null) {
        throw new IllegalStateException("Raid participant was not tracked: " + gameId.value()
            + "/" + playerId);
      }
      int count = extractedItems.stream().mapToInt(ItemInstance::quantity)
          .reduce(0, Math::addExact);
      long value = extractedItems.stream().mapToLong(item -> Math.multiplyExact(
          items.require(item.itemId()).basePrice().units(), item.quantity()))
          .reduce(0L, Math::addExact);
      long earnings = outcome == RaidOutcome.EXTRACTED ? value : 0L;
      result = new RaidResult(RaidResult.idFor(gameId, playerId), gameId, playerId,
          raid.mapId, raid.mode, raid.difficulty, outcome, raid.startedAt, time.now(),
          raid.kills, count, value, experience, earnings);
      terminal.put(key, result);
      active.remove(key);
    }
    return result;
  }

  /** Persists an already-frozen snapshot; safe to repeat with the same deterministic id. */
  public CompletableFuture<RaidResult> record(RaidResult result) {
    return reports.record(Preconditions.checkNotNull(result, "result"));
  }

  public int kills(CraftovGameId gameId, UUID playerId) {
    Key key = new Key(gameId, playerId);
    RaidResult result = terminal.get(key);
    if (result != null) return result.kills();
    MutableRaid raid = active.get(key);
    return raid == null ? 0 : raid.kills;
  }

  /** Releases bounded in-memory snapshots after all result futures for a room complete. */
  public void discard(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    active.keySet().removeIf(key -> key.gameId().equals(gameId));
    terminal.keySet().removeIf(key -> key.gameId().equals(gameId));
  }

  private record Key(CraftovGameId gameId, UUID playerId) {}

  private static final class MutableRaid {
    private final CraftovMapId mapId;
    private final RaidMode mode;
    private final RaidDifficulty difficulty;
    private Instant startedAt;
    private int kills;

    private MutableRaid(CraftovMapId mapId, RaidMode mode, RaidDifficulty difficulty,
        Instant startedAt) {
      this.mapId = mapId;
      this.mode = mode;
      this.difficulty = difficulty;
      this.startedAt = startedAt;
    }

  }
}
