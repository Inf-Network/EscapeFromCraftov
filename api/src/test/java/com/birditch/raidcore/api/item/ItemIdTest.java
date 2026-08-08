package com.birditch.raidcore.api.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ItemIdTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "ammo_556", "old_phone", "x9", "0"})
    void acceptsLowercaseDigitsAndUnderscore(String value) {
        assertEquals(value, ItemId.of(value).value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Upper", "has-dash", "has space", "ns:key", "dot.ted"})
    void rejectsAnythingOutsideThePattern(String value) {
        assertThrows(IllegalArgumentException.class, () -> ItemId.of(value));
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> ItemId.of(null));
    }

    @Test
    void ofEqualsCanonicalConstructor() {
        assertEquals(new ItemId("medkit"), ItemId.of("medkit"));
    }
}
