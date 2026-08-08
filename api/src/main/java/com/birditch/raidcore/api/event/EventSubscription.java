package com.birditch.raidcore.api.event;

/// Handle to an active event subscription; unsubscribing is idempotent.
public interface EventSubscription {

    void unsubscribe();

    boolean active();
}
