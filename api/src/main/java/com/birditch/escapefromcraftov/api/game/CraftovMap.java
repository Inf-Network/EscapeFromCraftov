package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Static description of a playable craftov map.
public record CraftovMap(CraftovMapId id, String displayName) {

    public CraftovMap {
        Preconditions.checkNotNull(id, "id");
        Preconditions.checkNotNull(displayName, "displayName");
        Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    }
}
