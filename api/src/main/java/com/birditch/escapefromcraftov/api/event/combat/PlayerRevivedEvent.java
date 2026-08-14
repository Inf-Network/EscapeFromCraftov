package com.birditch.escapefromcraftov.api.event.combat;

import com.birditch.escapefromcraftov.api.event.CraftovEvent;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// Published when a downed player has been revived by a rescuer.
public record PlayerRevivedEvent(UUID playerId, UUID rescuerId) implements CraftovEvent {

    public PlayerRevivedEvent {
        Preconditions.checkNotNull(playerId, "playerId");
        Preconditions.checkNotNull(rescuerId, "rescuerId");
    }
}
