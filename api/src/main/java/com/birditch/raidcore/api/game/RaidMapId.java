package com.birditch.raidcore.api.game;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a raid map; `value` must match `[a-z0-9_]+`.
public record RaidMapId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public RaidMapId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "RaidMapId must match [a-z0-9_]+ but was: " + value);
    }

    public static RaidMapId of(String value) {
        return new RaidMapId(value);
    }
}
