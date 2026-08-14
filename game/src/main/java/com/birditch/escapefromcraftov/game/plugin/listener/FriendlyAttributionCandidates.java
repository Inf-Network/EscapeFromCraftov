package com.birditch.escapefromcraftov.game.plugin.listener;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Main-thread event-lifecycle state for enabled friendly damage.
 *
 * <p>An identity map plus a per-victim stack keeps nested Bukkit damage events isolated.</p>
 */
final class FriendlyAttributionCandidates<E> {

  private final Map<E, Candidate<E>> byEvent = new IdentityHashMap<>();
  private final Map<UUID, ArrayDeque<Candidate<E>>> byVictim = new HashMap<>();

  void begin(UUID victimId, CraftovGameId gameId, E event) {
    Preconditions.checkNotNull(victimId, "victimId");
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(event, "event");
    Preconditions.checkState(!byEvent.containsKey(event),
        "friendly damage event is already pending");
    Candidate<E> candidate = new Candidate<>(victimId, gameId, event);
    byEvent.put(event, candidate);
    byVictim.computeIfAbsent(victimId, _ -> new ArrayDeque<>()).addLast(candidate);
  }

  /** Returns true only when this synchronous down transition belongs to a live candidate. */
  boolean confirmsDowned(UUID victimId, CraftovGameId gameId) {
    ArrayDeque<Candidate<E>> candidates = byVictim.get(victimId);
    Candidate<E> current = candidates == null ? null : candidates.peekLast();
    return current != null && current.gameId().equals(gameId);
  }

  /** Always removes this event candidate; true means an uncancelled hit should clear evidence. */
  boolean finish(E event, boolean cancelled, double finalDamage) {
    Candidate<E> candidate = byEvent.remove(event);
    if (candidate == null) return false;
    ArrayDeque<Candidate<E>> candidates = byVictim.get(candidate.victimId());
    if (candidates != null) {
      candidates.removeLastOccurrence(candidate);
      if (candidates.isEmpty()) byVictim.remove(candidate.victimId());
    }
    return GameCombatAttributionPolicy.clearsHostileAfterEvent(
        true, cancelled, finalDamage);
  }

  void clear(UUID playerId) {
    ArrayDeque<Candidate<E>> removed = byVictim.remove(playerId);
    if (removed != null) removed.forEach(candidate -> byEvent.remove(candidate.event()));
  }

  boolean pending(UUID victimId) {
    ArrayDeque<Candidate<E>> candidates = byVictim.get(victimId);
    return candidates != null && !candidates.isEmpty();
  }

  private record Candidate<E>(UUID victimId, CraftovGameId gameId, E event) {}
}
