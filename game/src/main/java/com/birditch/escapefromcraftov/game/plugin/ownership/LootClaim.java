package com.birditch.escapefromcraftov.game.plugin.ownership;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Set;
import java.util.UUID;

/** Immutable room-scoped priority ownership frozen when loot is created. */
public record LootClaim(
    CraftovGameId gameId,
    Set<UUID> priorityPlayers,
    long unlockAtMillis) {

  public LootClaim {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(priorityPlayers, "priorityPlayers");
    priorityPlayers = Set.copyOf(priorityPlayers);
  }

  public boolean protectedAt(long nowMillis) {
    return !priorityPlayers.isEmpty() && nowMillis < unlockAtMillis;
  }
}
