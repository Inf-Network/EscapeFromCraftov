package com.birditch.raidcore.api.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyTest {

    @Test
    void zeroConstantHasNoUnitsAndIsZero() {
        assertEquals(0L, Money.ZERO.units());
        assertTrue(Money.ZERO.isZero());
        assertFalse(Money.of(1).isZero());
    }

    @Test
    void ofRejectsNegativeUnits() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1));
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        void plusAddsUnits() {
            assertEquals(Money.of(1500), Money.of(1000).plus(Money.of(500)));
        }

        @Test
        void plusOverflowThrowsArithmeticException() {
            Money max = Money.of(Long.MAX_VALUE);
            assertThrows(ArithmeticException.class, () -> max.plus(Money.of(1)));
        }

        @Test
        void minusSubtractsUnits() {
            assertEquals(Money.of(400), Money.of(1000).minus(Money.of(600)));
        }

        @Test
        void minusToExactlyZeroIsAllowed() {
            assertEquals(Money.ZERO, Money.of(1000).minus(Money.of(1000)));
        }

        @Test
        void minusRejectsNegativeResult() {
            Money small = Money.of(500);
            assertThrows(IllegalArgumentException.class, () -> small.minus(Money.of(501)));
        }
    }

    @Nested
    @DisplayName("atLeast")
    class AtLeast {

        @Test
        void trueWhenGreaterOrEqual() {
            assertTrue(Money.of(1000).atLeast(Money.of(1000)));
            assertTrue(Money.of(1001).atLeast(Money.of(1000)));
        }

        @Test
        void falseWhenSmaller() {
            assertFalse(Money.of(999).atLeast(Money.of(1000)));
        }
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0",
            "999, 999",
            "1000, '1,000'",
            "12500, '12,500'",
            "1234567, '1,234,567'"
    })
    @DisplayName("format groups thousands with commas in ROOT locale")
    void formatGroupsThousands(long units, String expected) {
        assertEquals(expected, Money.of(units).format());
    }
}
