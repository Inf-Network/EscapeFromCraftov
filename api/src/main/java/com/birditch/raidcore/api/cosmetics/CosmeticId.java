package com.birditch.raidcore.api.cosmetics;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a cosmetic; `value` must match `[a-z0-9_]+`.
public record CosmeticId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public CosmeticId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "CosmeticId must match [a-z0-9_]+ but was: " + value);
    }

    public static CosmeticId of(String value) {
        return new CosmeticId(value);
    }
}
