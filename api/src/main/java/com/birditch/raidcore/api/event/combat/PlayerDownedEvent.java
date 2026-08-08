package com.birditch.raidcore.api.event.combat;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published when a player is downed during a raid.
public record PlayerDownedEvent(UUID playerId, RaidGameId gameId) implements RaidEvent {

    public PlayerDownedEvent {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
