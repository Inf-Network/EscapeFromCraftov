package com.birditch.raidcore.api.event.game;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published after a player has joined a raid game.
public record PlayerJoinedRaidEvent(RaidGameId gameId, UUID playerId) implements RaidEvent {

    public PlayerJoinedRaidEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(playerId, "playerId");
    }
}
