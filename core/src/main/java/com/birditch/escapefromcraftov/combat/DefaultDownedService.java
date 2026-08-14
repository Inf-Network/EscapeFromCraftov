package com.birditch.escapefromcraftov.combat;

import com.birditch.escapefromcraftov.api.combat.CombatService;
import com.birditch.escapefromcraftov.api.combat.DownedService;
import com.birditch.escapefromcraftov.api.combat.PlayerCombatState;
import com.birditch.escapefromcraftov.api.event.EventBus;
import com.birditch.escapefromcraftov.api.event.combat.PlayerDownedEvent;
import com.birditch.escapefromcraftov.api.event.combat.PlayerRevivedEvent;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// [DownedService] layered on [CombatService]: every transition goes through the
/// combat state and publishes its domain event after the state change.
///
/// Main-thread confined, like all domain state.
public final class DefaultDownedService implements DownedService {

  private final CombatService combat;
  private final EventBus eventBus;

  public DefaultDownedService(CombatService combat, EventBus eventBus) {
    this.combat = Preconditions.checkNotNull(combat, "combat");
    this.eventBus = Preconditions.checkNotNull(eventBus, "eventBus");
  }

  @Override
  public boolean isDowned(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return combat.stateOf(playerId) == PlayerCombatState.DOWNED;
  }

  @Override
  public void down(UUID playerId, CraftovGameId gameId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkState(combat.stateOf(playerId) != PlayerCombatState.DEAD,
        "Cannot down a dead player: " + playerId);
    combat.setState(playerId, PlayerCombatState.DOWNED);
    eventBus.publish(new PlayerDownedEvent(playerId, gameId));
  }

  @Override
  public void revive(UUID playerId, UUID rescuerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(rescuerId, "rescuerId");
    Preconditions.checkState(combat.stateOf(playerId) == PlayerCombatState.DOWNED,
        "Cannot revive a player who is not downed: " + playerId);
    combat.setState(playerId, PlayerCombatState.ALIVE);
    eventBus.publish(new PlayerRevivedEvent(playerId, rescuerId));
  }
}
