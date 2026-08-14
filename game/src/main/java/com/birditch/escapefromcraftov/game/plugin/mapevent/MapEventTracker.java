package com.birditch.escapefromcraftov.game.plugin.mapevent;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapEvent;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Main-thread state machine for the authored events of every live game. */
public final class MapEventTracker {

  public static final int MAXIMUM_SPAWN_ATTEMPTS = 60;

  private final Map<CraftovGameId, Map<String, Entry>> games = new HashMap<>();

  public void start(CraftovGameId gameId, Collection<CraftovMapEvent> events) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(events, "events");
    LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    for (CraftovMapEvent event : events) {
      Preconditions.checkNotNull(event, "event");
      if (event.enabled()) entries.put(event.id(), new Entry(event));
    }
    games.put(gameId, entries);
  }

  public boolean ready(CraftovGameId gameId, String eventId) {
    Entry entry = entry(gameId, eventId);
    if (entry == null || entry.state != State.PENDING) return false;
    entry.state = State.READY;
    return true;
  }

  public Optional<Activation> claim(CraftovGameId gameId, String eventId) {
    Entry entry = entry(gameId, eventId);
    if (entry == null || entry.state != State.READY
        || entry.attempts >= MAXIMUM_SPAWN_ATTEMPTS) return Optional.empty();
    entry.state = State.SPAWNING;
    entry.attempts++;
    return Optional.of(new Activation(gameId, entry.definition, entry.attempts));
  }

  /** Returns true when one rate-limited retry may still be scheduled. */
  public boolean retryUnloaded(Activation activation) {
    Entry entry = matching(activation);
    if (entry == null || entry.state != State.SPAWNING) return false;
    if (entry.attempts >= MAXIMUM_SPAWN_ATTEMPTS) {
      entry.state = State.FAILED;
      return false;
    }
    entry.state = State.PENDING;
    return true;
  }

  public boolean fail(Activation activation) {
    Entry entry = matching(activation);
    if (entry == null || entry.state != State.SPAWNING) return false;
    entry.state = State.FAILED;
    return true;
  }

  public boolean spawned(Activation activation, UUID entityId) {
    Preconditions.checkNotNull(entityId, "entityId");
    Entry entry = matching(activation);
    if (entry == null || entry.state != State.SPAWNING || entry.entityId != null) return false;
    entry.entityId = entityId;
    entry.state = State.ACTIVE;
    return true;
  }

  /** Releases a definitely absent/invalid active entity before a bounded retry. */
  public boolean lostActive(CraftovGameId gameId, String eventId, UUID entityId) {
    Entry entry = entry(gameId, eventId);
    if (entry == null || entry.state != State.ACTIVE || !entityId.equals(entry.entityId)) {
      return false;
    }
    entry.entityId = null;
    if (entry.attempts >= MAXIMUM_SPAWN_ATTEMPTS) {
      entry.state = State.FAILED;
      return false;
    }
    entry.state = State.PENDING;
    return true;
  }

  public Optional<Completion> complete(CraftovGameId gameId, String eventId, UUID entityId,
      Set<UUID> frozenTeam, long nowMillis) {
    Preconditions.checkNotNull(entityId, "entityId");
    Preconditions.checkNotNull(frozenTeam, "frozenTeam");
    Entry entry = entry(gameId, eventId);
    if (entry == null || entry.state != State.ACTIVE || !entityId.equals(entry.entityId)) {
      return Optional.empty();
    }
    entry.state = State.COMPLETED;
    entry.frozenTeam = Set.copyOf(frozenTeam);
    entry.protectedUntilMillis = entry.frozenTeam.isEmpty() ? nowMillis
        : saturatedAdd(nowMillis, entry.definition.protectionSeconds() * 1_000L);
    return Optional.of(new Completion(gameId, entry.definition, entry.entityId,
        entry.frozenTeam, entry.protectedUntilMillis));
  }

  public CacheAccess cacheAccess(CraftovGameId gameId, String lootPointId, UUID playerId,
      long nowMillis) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(lootPointId, "lootPointId");
    Preconditions.checkNotNull(playerId, "playerId");
    Map<String, Entry> entries = games.get(gameId);
    if (entries == null) return CacheAccess.unrestricted();
    List<Entry> caches = entries.values().stream()
        .filter(entry -> entry.definition.cacheLootPointId().equals(lootPointId)).toList();
    if (caches.isEmpty()) return CacheAccess.unrestricted();
    for (Entry entry : caches) {
      if (entry.state != State.COMPLETED) return CacheAccess.locked();
    }
    long latestProtection = nowMillis;
    for (Entry entry : caches) {
      if (nowMillis < entry.protectedUntilMillis && !entry.frozenTeam.contains(playerId)) {
        latestProtection = Math.max(latestProtection, entry.protectedUntilMillis);
      }
    }
    if (latestProtection > nowMillis) {
      return CacheAccess.protectedFor(secondsRemaining(nowMillis, latestProtection));
    }
    long ownProtection = caches.stream()
        .filter(entry -> entry.frozenTeam.contains(playerId))
        .mapToLong(entry -> entry.protectedUntilMillis).max().orElse(nowMillis);
    return ownProtection > nowMillis
        ? CacheAccess.priority(secondsRemaining(nowMillis, ownProtection))
        : CacheAccess.publicAccess();
  }

  public Set<String> eventZoneIds(CraftovGameId gameId) {
    Map<String, Entry> entries = games.get(gameId);
    if (entries == null) return Set.of();
    return entries.values().stream().map(entry -> entry.definition.aiZoneId())
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  public Optional<CraftovMapEvent> event(CraftovGameId gameId, String eventId) {
    Entry entry = entry(gameId, eventId);
    return entry == null ? Optional.empty() : Optional.of(entry.definition);
  }

  public boolean ownsActive(CraftovGameId gameId, String eventId, UUID entityId) {
    Entry entry = entry(gameId, eventId);
    return entry != null && entry.state == State.ACTIVE && entityId.equals(entry.entityId);
  }

  public State state(CraftovGameId gameId, String eventId) {
    Entry entry = entry(gameId, eventId);
    return entry == null ? State.ABSENT : entry.state;
  }

  public int attempts(CraftovGameId gameId, String eventId) {
    Entry entry = entry(gameId, eventId);
    return entry == null ? 0 : entry.attempts;
  }

  public void clear(CraftovGameId gameId) {
    games.remove(Preconditions.checkNotNull(gameId, "gameId"));
  }

  public void clear() {
    games.clear();
  }

  private Entry matching(Activation activation) {
    Preconditions.checkNotNull(activation, "activation");
    Entry entry = entry(activation.gameId(), activation.event().id());
    return entry != null && entry.attempts == activation.attempt() ? entry : null;
  }

  private Entry entry(CraftovGameId gameId, String eventId) {
    Map<String, Entry> entries = games.get(gameId);
    return entries == null ? null : entries.get(eventId);
  }

  private static long saturatedAdd(long left, long right) {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException ignored) {
      return Long.MAX_VALUE;
    }
  }

  private static long secondsRemaining(long nowMillis, long untilMillis) {
    long remaining = Math.max(0, untilMillis - nowMillis);
    return Math.max(1, (remaining + 999) / 1_000);
  }

  public enum State {
    ABSENT,
    PENDING,
    READY,
    SPAWNING,
    ACTIVE,
    COMPLETED,
    FAILED
  }

  public enum CacheAccessKind {
    UNRESTRICTED,
    LOCKED,
    PROTECTED,
    PRIORITY,
    PUBLIC
  }

  public record Activation(CraftovGameId gameId, CraftovMapEvent event, int attempt) {}

  public record Completion(CraftovGameId gameId, CraftovMapEvent event, UUID entityId,
                           Set<UUID> frozenTeam, long protectedUntilMillis) {}

  public record CacheAccess(CacheAccessKind kind, long secondsRemaining) {
    public boolean allowed() {
      return kind == CacheAccessKind.UNRESTRICTED || kind == CacheAccessKind.PRIORITY
          || kind == CacheAccessKind.PUBLIC;
    }

    private static CacheAccess unrestricted() {
      return new CacheAccess(CacheAccessKind.UNRESTRICTED, 0);
    }

    private static CacheAccess locked() {
      return new CacheAccess(CacheAccessKind.LOCKED, 0);
    }

    private static CacheAccess protectedFor(long seconds) {
      return new CacheAccess(CacheAccessKind.PROTECTED, seconds);
    }

    private static CacheAccess priority(long seconds) {
      return new CacheAccess(CacheAccessKind.PRIORITY, seconds);
    }

    private static CacheAccess publicAccess() {
      return new CacheAccess(CacheAccessKind.PUBLIC, 0);
    }
  }

  private static final class Entry {
    private final CraftovMapEvent definition;
    private State state = State.PENDING;
    private int attempts;
    private UUID entityId;
    private Set<UUID> frozenTeam = Set.of();
    private long protectedUntilMillis;

    private Entry(CraftovMapEvent definition) {
      this.definition = definition;
    }
  }
}
