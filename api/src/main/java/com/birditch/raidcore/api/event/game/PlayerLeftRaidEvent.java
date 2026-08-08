package com.birditch.raidcore.api.event.game;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published after a player has left (or been removed from) a raid game.
public record PlayerLeftRaidEvent(RaidGameId gameId, UUID playerId) implements RaidEvent {

    public PlayerLeftRaidEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
    }
}
