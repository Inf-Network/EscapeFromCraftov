package com.birditch.raidcore.common.check;

import org.jspecify.annotations.Nullable;

/// Validation helpers used across all RaidCore modules for constructor and
/// method argument checks.
public final class Preconditions {

    private Preconditions() {
    }

    /// @throws IllegalArgumentException if `expr` is `false`
    public static void checkArgument(boolean expr, String message) {
        if (!expr) {
            throw new IllegalArgumentException(message);
        }
    }

    /// @throws IllegalStateException if `expr` is `false`
    public static void checkState(boolean expr, String message) {
        if (!expr) {
            throw new IllegalStateException(message);
        }
    }

    /// Returns `value` unchanged when non-null.
    ///
    /// @throws NullPointerException with message `"<name> must not be null"`
    public static <T> T checkNotNull(@Nullable T value, String name) {
        if (value == null) {
            throw new NullPointerException(name + " must not be null");
        }
        return value;
    }
}
