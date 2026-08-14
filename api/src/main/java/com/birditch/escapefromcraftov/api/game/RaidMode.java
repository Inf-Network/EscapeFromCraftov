package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Locale;

/// Stable raid rule set selected before matchmaking.
public enum RaidMode {
    NORMAL,
    CONFIDENTIAL;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RaidMode parse(String value) {
        Preconditions.checkNotNull(value, "value");
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
