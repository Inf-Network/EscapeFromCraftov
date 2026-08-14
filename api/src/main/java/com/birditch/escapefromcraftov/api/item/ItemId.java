package com.birditch.escapefromcraftov.api.item;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of an item definition; `value` must match `[a-z0-9_]+`.
public record ItemId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public ItemId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "ItemId must match [a-z0-9_]+ but was: " + value);
    }

    public static ItemId of(String value) {
        return new ItemId(value);
    }
}
