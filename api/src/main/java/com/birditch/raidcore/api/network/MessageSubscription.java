package com.birditch.raidcore.api.network;

/// Handle to an active broker channel subscription; unsubscribing is
/// idempotent.
public interface MessageSubscription {

    void unsubscribe();

    boolean active();
}
