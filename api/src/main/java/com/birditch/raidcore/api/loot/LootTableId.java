package com.birditch.raidcore.api.loot;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of a loot table; `value` must match `[a-z0-9_]+`.
public record LootTableId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public LootTableId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "LootTableId must match [a-z0-9_]+ but was: " + value);
    }

    public static LootTableId of(String value) {
        return new LootTableId(value);
    }
}
