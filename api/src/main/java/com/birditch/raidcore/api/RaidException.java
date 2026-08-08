package com.birditch.raidcore.api;

/// Base unchecked exception for all RaidCore domain failures.
public class RaidException extends RuntimeException {

    public RaidException(String message) {
        super(message);
    }

    public RaidException(String message, Throwable cause) {
        super(message, cause);
    }
}
