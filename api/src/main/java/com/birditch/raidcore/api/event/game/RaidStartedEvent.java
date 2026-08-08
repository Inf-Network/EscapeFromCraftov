package com.birditch.raidcore.api.event.game;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.common.check.Preconditions;

/// Published when a raid game transitions into its running phase.
public record RaidStartedEvent(RaidGameId gameId) implements RaidEvent {

    public RaidStartedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
    }
}
