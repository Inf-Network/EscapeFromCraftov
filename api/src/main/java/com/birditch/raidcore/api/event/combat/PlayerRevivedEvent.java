package com.birditch.raidcore.api.event.combat;

import com.birditch.raidcore.api.event.RaidEvent;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.UUID;

/// Published when a downed player has been revived by a rescuer.
public record PlayerRevivedEvent(UUID playerId, UUID rescuerId) implements RaidEvent {

    public PlayerRevivedEvent {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(rescuerId, "rescuerId");
    }
}
