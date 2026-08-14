package com.birditch.escapefromcraftov.common.time;

import java.time.Instant;

/// Source of the current wall-clock time.
///
/// Inject instead of calling [System#currentTimeMillis()] directly so tests
/// can substitute a deterministic clock.
public interface TimeSource {

    long currentMillis();

    default Instant now() {
        return Instant.ofEpochMilli(currentMillis());
    }
}
