package com.birditch.escapefromcraftov.api.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ExtractionPointTest {

    private static final ExtractionPointId ID = ExtractionPointId.of("north_gate");
    private static final Duration TEN_SECONDS = Duration.ofSeconds(10);

    private static ExtractionPoint point(ExtractionState initialState) {
        return new ExtractionPoint(ID, "North Gate", ExtractionType.NORMAL,
                TEN_SECONDS, List.of(), initialState);
    }

    @Test
    void exposesItsConfiguration() {
        ExtractionPoint point = point(ExtractionState.AVAILABLE);
        assertEquals(ID, point.id());
        assertEquals("North Gate", point.displayName());
        assertEquals(ExtractionType.NORMAL, point.type());
        assertEquals(TEN_SECONDS, point.duration());
        assertEquals(List.of(), point.conditions());
        assertEquals(ExtractionState.AVAILABLE, point.state());
    }

    @Nested
    @DisplayName("construction")
    class Construction {

        @ParameterizedTest
        @EnumSource(names = {"LOCKED", "AVAILABLE"})
        void acceptsLockedAndAvailableInitialStates(ExtractionState initialState) {
            assertEquals(initialState, point(initialState).state());
        }

        @ParameterizedTest
        @EnumSource(names = {"EXTRACTING", "COMPLETED", "FAILED"})
        void rejectsSessionStatesAsInitialState(ExtractionState initialState) {
            assertThrows(IllegalArgumentException.class, () -> point(initialState));
        }

        @Test
        void rejectsBlankDisplayName() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExtractionPoint(ID, " ", ExtractionType.NORMAL,
                            TEN_SECONDS, List.of(), ExtractionState.AVAILABLE));
        }

        @Test
        void rejectsNegativeDuration() {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExtractionPoint(ID, "North Gate", ExtractionType.NORMAL,
                            Duration.ofSeconds(-1), List.of(), ExtractionState.AVAILABLE));
        }

        @Test
        void copiesConditionsDefensively() {
            ExtractionCondition condition = new ExtractionCondition() {
                @Override
                public String describe() {
                    return "always";
                }

                @Override
                public boolean isSatisfied(ExtractionContext context) {
                    return true;
                }
            };
            List<ExtractionCondition> source = new ArrayList<>(List.of(condition));
            ExtractionPoint point = new ExtractionPoint(ID, "North Gate", ExtractionType.NORMAL,
                    TEN_SECONDS, source, ExtractionState.AVAILABLE);
            source.clear();
            assertEquals(List.of(condition), point.conditions());
            List<ExtractionCondition> exposed = point.conditions();
            assertThrows(UnsupportedOperationException.class, () -> exposed.add(condition));
        }
    }

    @Nested
    @DisplayName("lock / unlock")
    class LockUnlock {

        @Test
        void unlockMovesLockedToAvailable() {
            ExtractionPoint point = point(ExtractionState.LOCKED);
            point.unlock();
            assertEquals(ExtractionState.AVAILABLE, point.state());
        }

        @Test
        void unlockWhenAlreadyAvailableThrowsIllegalStateException() {
            ExtractionPoint point = point(ExtractionState.AVAILABLE);
            assertThrows(IllegalStateException.class, point::unlock);
        }

        @Test
        void lockMovesAvailableToLocked() {
            ExtractionPoint point = point(ExtractionState.AVAILABLE);
            point.lock();
            assertEquals(ExtractionState.LOCKED, point.state());
        }

        @Test
        void lockWhenAlreadyLockedThrowsIllegalStateException() {
            ExtractionPoint point = point(ExtractionState.LOCKED);
            assertThrows(IllegalStateException.class, point::lock);
        }

        @Test
        void togglesRoundTrip() {
            ExtractionPoint point = point(ExtractionState.LOCKED);
            point.unlock();
            point.lock();
            assertEquals(ExtractionState.LOCKED, point.state());
        }
    }
}
