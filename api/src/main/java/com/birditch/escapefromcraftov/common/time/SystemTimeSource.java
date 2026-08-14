package com.birditch.escapefromcraftov.common.time;

/// Production [TimeSource] backed by [System#currentTimeMillis()].
public final class SystemTimeSource implements TimeSource {

    @Override
    public long currentMillis() {
        return System.currentTimeMillis();
    }
}
