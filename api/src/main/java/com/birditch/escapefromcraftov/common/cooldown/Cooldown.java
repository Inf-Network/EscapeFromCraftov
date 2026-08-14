package com.birditch.escapefromcraftov.common.cooldown;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.common.time.TimeSource;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/// Per-key cooldown tracker.
///
/// Main-thread confined: backed by a plain [HashMap] and never safe for
/// concurrent access. Expired entries are evicted lazily on lookup.
public final class Cooldown<K> {

    private final long durationMillis;
    private final TimeSource timeSource;
    private final Map<K, Long> expiries = new HashMap<>();

    public Cooldown(Duration duration, TimeSource timeSource) {
        Preconditions.checkNotNull(duration, "duration");
        Preconditions.checkArgument(!duration.isNegative(), "duration must not be negative");
        this.durationMillis = duration.toMillis();
        this.timeSource = Preconditions.checkNotNull(timeSource, "timeSource");
    }

    /// Starts the cooldown for `key` when it is ready; returns `false` while
    /// the key is still cooling down.
    public boolean tryUse(K key) {
        Preconditions.checkNotNull(key, "key");
        long now = timeSource.currentMillis();
        Long expiresAt = expiries.get(key);
        if (expiresAt != null && expiresAt > now) {
            return false;
        }
        expiries.put(key, now + durationMillis);
        return true;
    }

    /// Remaining cooldown for `key`; [Duration#ZERO] when ready.
    public Duration remaining(K key) {
        Preconditions.checkNotNull(key, "key");
        Long expiresAt = expiries.get(key);
        if (expiresAt == null) {
            return Duration.ZERO;
        }
        long now = timeSource.currentMillis();
        if (expiresAt <= now) {
            expiries.remove(key);
            return Duration.ZERO;
        }
        return Duration.ofMillis(expiresAt - now);
    }

    public void clear(K key) {
        Preconditions.checkNotNull(key, "key");
        expiries.remove(key);
    }
}
