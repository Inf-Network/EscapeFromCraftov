package com.birditch.raidcore.common.result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ResultTest {

    @Nested
    @DisplayName("success")
    class Success {

        @Test
        void okIsSuccessCarryingTheValue() {
            Result<String> result = Result.ok("loot");
            assertTrue(result.isSuccess());
            assertEquals(Optional.of("loot"), result.value());
            assertEquals(Optional.empty(), result.error());
        }

        @Test
        void okYieldsSuccessRecordExposingTheValue() {
            Result<String> result = Result.ok("loot");
            Result.Success<?> success = assertInstanceOf(Result.Success.class, result);
            assertEquals("loot", success.result());
        }

        @Test
        void okRejectsNullValue() {
            assertThrows(NullPointerException.class, () -> Result.ok(null));
        }

        @Test
        void mapTransformsTheValue() {
            Result<Integer> mapped = Result.ok(21).map(units -> units * 2);
            assertEquals(Optional.of(42), mapped.value());
        }

        @Test
        void orElseThrowReturnsTheValue() {
            assertEquals("loot", Result.ok("loot").orElseThrow(IllegalStateException::new));
        }
    }

    @Nested
    @DisplayName("failure")
    class Failure {

        @Test
        void failIsFailureCarryingTheMessage() {
            Result<String> result = Result.fail("Game not found");
            assertFalse(result.isSuccess());
            assertEquals(Optional.empty(), result.value());
            assertEquals(Optional.of("Game not found"), result.error());
        }

        @Test
        void failRejectsNullMessage() {
            assertThrows(NullPointerException.class, () -> Result.fail(null));
        }

        @Test
        void mapPropagatesTheFailureUnchanged() {
            Result<String> failure = Result.fail("Game not found");
            Result<Integer> mapped = failure.map(String::length);
            assertFalse(mapped.isSuccess());
            assertEquals(Optional.of("Game not found"), mapped.error());
        }

        @Test
        void mapNeverInvokesTheMapperOnFailure() {
            Result<String> failure = Result.fail("Game not found");
            failure.map(value -> {
                throw new AssertionError("mapper must not run for " + value);
            });
        }

        @Test
        void orElseThrowBuildsTheExceptionFromTheMessage() {
            Result<String> failure = Result.fail("Game not found");
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> failure.orElseThrow(IllegalStateException::new));
            assertEquals("Game not found", e.getMessage());
        }

        @Test
        void orElseThrowThrowsTheExactFactoryInstance() {
            RuntimeException custom = new RuntimeException("boom");
            Result<String> failure = Result.fail("ignored");
            RuntimeException thrown = assertThrows(RuntimeException.class,
                    () -> failure.orElseThrow(_ -> custom));
            assertSame(custom, thrown);
        }
    }
}
