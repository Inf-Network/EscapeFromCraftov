package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a raid game instance; `value` must match `[a-z0-9-]+`.
public record RaidGameId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9-]+");

    public RaidGameId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "RaidGameId must match [a-z0-9-]+ but was: " + value);
    }

    public static RaidGameId of(String value) {
        return new RaidGameId(value);
    }
}
