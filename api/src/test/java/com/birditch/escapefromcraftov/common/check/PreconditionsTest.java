package com.birditch.escapefromcraftov.common.check;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class PreconditionsTest {

    @Nested
    @DisplayName("checkArgument")
    class CheckArgument {

        @Test
        void passesWhenExpressionIsTrue() {
            assertDoesNotThrow(() -> Preconditions.checkArgument(true, "unused"));
        }

        @Test
        void throwsIllegalArgumentExceptionWithMessageWhenFalse() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Preconditions.checkArgument(false, "amount must be positive"));
            assertEquals("amount must be positive", e.getMessage());
        }
    }

    @Nested
    @DisplayName("checkState")
    class CheckState {

        @Test
        void passesWhenExpressionIsTrue() {
            assertDoesNotThrow(() -> Preconditions.checkState(true, "unused"));
        }

        @Test
        void throwsIllegalStateExceptionWithMessageWhenFalse() {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> Preconditions.checkState(false, "registry already frozen"));
            assertEquals("registry already frozen", e.getMessage());
        }
    }

    @Nested
    @DisplayName("checkNotNull")
    class CheckNotNull {

        @Test
        void returnsTheSameInstanceWhenNonNull() {
            Object value = new Object();
            assertSame(value, Preconditions.checkNotNull(value, "value"));
        }

        @Test
        void throwsNullPointerExceptionNamingTheParameter() {
            NullPointerException e = assertThrows(NullPointerException.class,
                    () -> Preconditions.checkNotNull(null, "playerId"));
            assertEquals("playerId must not be null", e.getMessage());
        }
    }
}
