package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;

/// Static description of a playable raid map.
public record RaidMap(RaidMapId id, String displayName) {

    public RaidMap {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    }
}
