package com.birditch.raidcore.api.extraction;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.regex.Pattern;

/// Identifier of an extraction point; `value` must match `[a-z0-9_]+`.
public record ExtractionPointId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9_]+");

    public ExtractionPointId {
        Preconditions.checkNotNull(value, "value");
        Preconditions.checkArgument(PATTERN.matcher(value).matches(),
                "ExtractionPointId must match [a-z0-9_]+ but was: " + value);
    }

    public static ExtractionPointId of(String value) {
        return new ExtractionPointId(value);
    }
}
