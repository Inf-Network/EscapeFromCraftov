package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a craftov game instance; `value` must match `[a-z0-9-]+`.
public record CraftovGameId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9-]+");

    public CraftovGameId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "CraftovGameId must match [a-z0-9-]+ but was: " + value);
    }

    public static CraftovGameId of(String value) {
        return new CraftovGameId(value);
    }
}
