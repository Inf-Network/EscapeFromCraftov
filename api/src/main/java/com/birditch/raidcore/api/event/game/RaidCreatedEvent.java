package com.birditch.raidcore.api.event.game;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidMapId;
import com.birditch.raidcore.common.check.Preconditions;

/// Published after a raid game has been created and is waiting for players.
public record RaidCreatedEvent(RaidGameId gameId, RaidMapId mapId) implements RaidEvent {

    public RaidCreatedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(mapId, "mapId");
    }
}
