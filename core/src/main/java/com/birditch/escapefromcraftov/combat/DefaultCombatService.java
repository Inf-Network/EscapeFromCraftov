package com.birditch.escapefromcraftov.combat;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// [CombatService] backed by a plain [HashMap]. Untracked players are
/// [PlayerCombatState#ALIVE]; `reset` removes the entry, returning the player
/// to that untracked default.
///
/// Not thread-safe: all access is main-thread confined.
public final class DefaultCombatService implements CombatService {

  private final Map<UUID, PlayerCombatState> states = new HashMap<>();

  @Override
  public PlayerCombatState stateOf(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return states.getOrDefault(playerId, PlayerCombatState.ALIVE);
  }

  @Override
  public void setState(UUID playerId, PlayerCombatState state) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(state, "state");
    states.put(playerId, state);
  }

  @Override
  public void reset(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    states.remove(playerId);
  }
}
