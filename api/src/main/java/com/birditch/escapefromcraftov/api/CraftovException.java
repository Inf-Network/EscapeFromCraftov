package com.birditch.escapefromcraftov.api;

/// Base unchecked exception for all EscapeFromCraftov domain failures.
public class CraftovException extends RuntimeException {

    public CraftovException(String message) {
        super(message);
    }

    public CraftovException(String message, Throwable cause) {
        super(message, cause);
    }
}
