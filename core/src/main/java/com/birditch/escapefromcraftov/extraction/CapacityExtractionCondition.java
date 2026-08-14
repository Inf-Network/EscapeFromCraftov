package com.birditch.escapefromcraftov.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionCondition;
import com.birditch.escapefromcraftov.api.extraction.ExtractionContext;
import com.birditch.escapefromcraftov.api.extraction.ExtractionPermit;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Finite per-raid extraction seats with reservation semantics.
 *
 * <p>A seat is reserved when the countdown starts, returned when the player
 * leaves the area or settlement fails, and consumed only when durable inventory
 * settlement succeeds. This prevents two simultaneous countdowns from both
 * claiming the last seat.</p>
 */
public final class CapacityExtractionCondition implements ExtractionCondition {

  private final int capacity;
  private final Map<CraftovGameId, State> states = new HashMap<>();

  public CapacityExtractionCondition(int capacity) {
    Preconditions.checkArgument(capacity > 0, "capacity must be positive");
    this.capacity = capacity;
  }

  public int capacity() {
    return capacity;
  }

  public synchronized int remainingSlots(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    State state = states.get(gameId);
    return state == null ? capacity
        : Math.max(0, capacity - state.consumed - state.reserved.size());
  }

  @Override
  public String describe() {
    return "撤离名额已满（本局上限 " + capacity + " 人）";
  }

  @Override
  public synchronized boolean isSatisfied(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    return remainingSlots(context.gameId()) > 0;
  }

  @Override
  public synchronized Optional<ExtractionPermit> acquire(ExtractionContext context) {
    Preconditions.checkNotNull(context, "context");
    State state = states.computeIfAbsent(context.gameId(), ignored -> new State());
    if (state.reserved.contains(context.playerId())
        || state.consumed + state.reserved.size() >= capacity) {
      return Optional.empty();
    }
    state.reserved.add(context.playerId());
    return Optional.of(new Permit(context.gameId(), context.playerId()));
  }

  private synchronized void release(CraftovGameId gameId, UUID playerId) {
    State state = states.get(gameId);
    if (state == null) return;
    state.reserved.remove(playerId);
    prune(gameId, state);
  }

  private synchronized void commit(CraftovGameId gameId, UUID playerId) {
    State state = states.get(gameId);
    if (state == null || !state.reserved.remove(playerId)) return;
    state.consumed++;
  }

  private void prune(CraftovGameId gameId, State state) {
    if (state.consumed == 0 && state.reserved.isEmpty()) states.remove(gameId);
  }

  private final class Permit implements ExtractionPermit {

    private final CraftovGameId gameId;
    private final UUID playerId;
    private boolean terminal;

    private Permit(CraftovGameId gameId, UUID playerId) {
      this.gameId = gameId;
      this.playerId = playerId;
    }

    @Override
    public CompletableFuture<Boolean> authorize() {
      return CompletableFuture.completedFuture(!terminal);
    }

    @Override
    public synchronized void commit() {
      if (terminal) return;
      terminal = true;
      CapacityExtractionCondition.this.commit(gameId, playerId);
    }

    @Override
    public synchronized void release() {
      if (terminal) return;
      terminal = true;
      CapacityExtractionCondition.this.release(gameId, playerId);
    }
  }

  private static final class State {
    private final Set<UUID> reserved = new HashSet<>();
    private int consumed;
  }
}
