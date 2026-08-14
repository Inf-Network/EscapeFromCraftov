package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Locale;

/// Stable matchmaking difficulty. Runtime systems may map this to their own
/// numeric multipliers without changing the Redis wire value.
public enum RaidDifficulty {
    EASY,
    NORMAL,
    HARD;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static RaidDifficulty parse(String value) {
        Preconditions.checkNotNull(value, "value");
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public double multiplier() {
        return switch (this) {
            case EASY -> 0.75;
            case NORMAL -> 1.0;
            case HARD -> 1.5;
        };
    }
}
