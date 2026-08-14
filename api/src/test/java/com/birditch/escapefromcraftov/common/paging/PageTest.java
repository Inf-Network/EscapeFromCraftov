package com.birditch.escapefromcraftov.common.paging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PageTest {

    private static final List<Integer> TEN = IntStream.rangeClosed(1, 10).boxed().toList();

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        void emptySourceYieldsPageOneWithZeroTotals() {
            Page<Integer> page = Page.of(List.of(), 3, 5);
            assertEquals(List.of(), page.items());
            assertEquals(1, page.page());
            assertEquals(5, page.pageSize());
            assertEquals(0, page.totalItems());
            assertEquals(0, page.totalPages());
        }

        @Test
        void slicesTheRequestedPage() {
            Page<Integer> page = Page.of(TEN, 2, 3);
            assertEquals(List.of(4, 5, 6), page.items());
            assertEquals(2, page.page());
            assertEquals(10, page.totalItems());
            assertEquals(4, page.totalPages());
        }

        @Test
        void lastPageMayBePartial() {
            Page<Integer> page = Page.of(TEN, 4, 3);
            assertEquals(List.of(10), page.items());
            assertEquals(4, page.page());
        }

        @ParameterizedTest
        @CsvSource({"0, 1", "-5, 1", "5, 4", "99, 4"})
        @DisplayName("out-of-range page numbers clamp into the valid range")
        void clampsPageIntoRange(int requested, int expected) {
            assertEquals(expected, Page.of(TEN, requested, 3).page());
        }

        @Test
        void rejectsNonPositivePageSize() {
            assertThrows(IllegalArgumentException.class, () -> Page.of(TEN, 1, 0));
        }
    }

    @Test
    void itemsAreImmutable() {
        Page<Integer> page = Page.of(TEN, 1, 3);
        List<Integer> items = page.items();
        assertThrows(UnsupportedOperationException.class, () -> items.add(99));
    }

    @Test
    void constructorRejectsInvalidNumbers() {
        List<String> items = List.of("x");
        assertThrows(IllegalArgumentException.class, () -> new Page<>(items, 0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(items, 1, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(items, 1, 1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Page<>(items, 1, 1, 1, -1));
    }

    @Test
    void constructorCopiesTheItemsDefensively() {
        List<String> source = new ArrayList<>(List.of("a"));
        Page<String> page = new Page<>(source, 1, 1, 1, 1);
        source.add("b");
        assertEquals(List.of("a"), page.items());
    }
}
