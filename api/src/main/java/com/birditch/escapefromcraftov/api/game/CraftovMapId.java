package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a craftov map; `value` must match `[a-z0-9_]+`.
public record CraftovMapId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public CraftovMapId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "CraftovMapId must match [a-z0-9_]+ but was: " + value);
    }

    public static CraftovMapId of(String value) {
        return new CraftovMapId(value);
    }
}
