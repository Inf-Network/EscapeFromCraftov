package com.birditch.raidcore.api.testing;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.common.time.TimeSource;
import java.time.Duration;

/// Manually controlled [TimeSource] for deterministic tests.
public final class MutableTimeSource implements TimeSource {

    private long currentMillis;

    public MutableTimeSource(long startMillis) {
        currentMillis = startMillis;
    }

    @Override
    public long currentMillis() {
        return currentMillis;
    }

    public void set(long millis) {
        currentMillis = millis;
    }

    public void advance(Duration d) {
        Preconditions.checkNotNull(d, "d");
        currentMillis = Math.addExact(currentMillis, d.toMillis());
    }
}
