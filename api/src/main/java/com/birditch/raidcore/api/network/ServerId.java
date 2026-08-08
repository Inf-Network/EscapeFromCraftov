package com.birditch.raidcore.api.network;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a game server in the network; `value` must match `[a-z0-9_-]+`.
public record ServerId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_-]+");

    public ServerId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "ServerId must match [a-z0-9_-]+ but was: " + value);
    }

    public static ServerId of(String value) {
        return new ServerId(value);
    }
}
