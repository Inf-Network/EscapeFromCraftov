package com.birditch.raidcore.api.event.game;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.api.game.RaidGameId;
import com.birditch.raidcore.api.game.RaidResult;
import com.birditch.raidcore.common.check.Preconditions;

/// Published after a raid game has finished and its result is available.
public record RaidEndedEvent(RaidGameId gameId, RaidResult result) implements RaidEvent {

    public RaidEndedEvent {
        Preconditions.checkNotNull(gameId, "gameId");
        Preconditions.checkNotNull(result, "result");
    }
}
