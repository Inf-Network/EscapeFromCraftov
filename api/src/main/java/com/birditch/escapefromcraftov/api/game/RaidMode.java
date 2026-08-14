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

    /** Intrinsic PvE pressure for this rule set, independent of chosen difficulty. */
    public double aiMultiplier() {
        return switch (this) {
            case NORMAL -> 1.0;
            case CONFIDENTIAL -> 1.25;
        };
    }

    /** Number of complete loot-table rolls produced by one unopened container. */
    public int lootRolls() {
        return switch (this) {
            case NORMAL -> 1;
            case CONFIDENTIAL -> 2;
        };
    }
}
