package com.birditch.raidcore.common.id;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NamespacedIdTest {

    @Test
    void ofUsesTheDefaultRaidcoreNamespace() {
        NamespacedId id = NamespacedId.of("medkit");
        assertEquals("raidcore", id.namespace());
        assertEquals("medkit", id.key());
    }

    @Test
    void toStringJoinsNamespaceAndKeyWithColon() {
        assertEquals("minecraft:stone", new NamespacedId("minecraft", "stone").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "item_1", "a.b-c_d", "0-9"})
    void acceptsLowercaseDigitsUnderscoreDotAndDash(String part) {
        NamespacedId id = new NamespacedId(part, part);
        assertEquals(part, id.namespace());
        assertEquals(part, id.key());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UPPER", "has space", "colon:inside", "emoji✨"})
    void rejectsInvalidNamespace(String namespace) {
        assertThrows(IllegalArgumentException.class, () -> new NamespacedId(namespace, "key"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "UPPER", "has space", "colon:inside"})
    void rejectsInvalidKey(String key) {
        assertThrows(IllegalArgumentException.class, () -> new NamespacedId("ns", key));
    }

    @Test
    void rejectsNullParts() {
        assertThrows(NullPointerException.class, () -> new NamespacedId(null, "key"));
        assertThrows(NullPointerException.class, () -> new NamespacedId("ns", null));
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        void splitsNamespaceAndKeyAtTheColon() {
            assertEquals(new NamespacedId("minecraft", "stone"), NamespacedId.parse("minecraft:stone"));
        }

        @Test
        void treatsBareKeyAsDefaultNamespace() {
            assertEquals(NamespacedId.of("medkit"), NamespacedId.parse("medkit"));
        }

        @Test
        void splitsAtTheFirstColonSoASecondColonInvalidatesTheKey() {
            assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse("a:b:c"));
        }

        @Test
        void rejectsEmptyKeyAfterColon() {
            assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse("ns:"));
        }

        @Test
        void rejectsNullText() {
            assertThrows(NullPointerException.class, () -> NamespacedId.parse(null));
        }
    }
}
