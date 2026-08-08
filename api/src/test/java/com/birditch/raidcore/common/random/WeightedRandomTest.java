package com.birditch.raidcore.common.random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WeightedRandomTest {

    @Nested
    @DisplayName("builder")
    class BuilderContract {

        @ParameterizedTest
        @ValueSource(ints = {0, -1, -100})
        void rejectsNonPositiveWeight(int weight) {
            WeightedRandom.Builder<String> builder = WeightedRandom.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.add("value", weight));
        }

        @Test
        void rejectsNullValue() {
            WeightedRandom.Builder<String> builder = WeightedRandom.builder();
            assertThrows(NullPointerException.class, () -> builder.add(null, 1));
        }

        @Test
        void buildWithoutEntriesThrowsIllegalStateException() {
            WeightedRandom.Builder<String> builder = WeightedRandom.builder();
            assertThrows(IllegalStateException.class, builder::build);
        }

        @Test
        void buildSumsTotalWeightAndCountsEntries() {
            WeightedRandom<String> random = WeightedRandom.<String>builder()
                    .add("a", 3)
                    .add("b", 7)
                    .build();
            assertEquals(10, random.totalWeight());
            assertEquals(2, random.size());
        }
    }

    @Nested
    @DisplayName("pick")
    class Pick {

        @Test
        void singleEntryIsAlwaysPicked() {
            WeightedRandom<String> random = WeightedRandom.<String>builder()
                    .add("only", 5)
                    .build();
            RandomProvider provider = new SeededRandomProvider(1L);
            for (int i = 0; i < 100; i++) {
                assertEquals("only", random.pick(provider));
            }
        }

        @Test
        @DisplayName("seeded distribution roughly follows the configured weights")
        void seededDistributionFollowsWeights() {
            WeightedRandom<String> random = WeightedRandom.<String>builder()
                    .add("common", 3)
                    .add("rare", 1)
                    .build();
            RandomProvider provider = new SeededRandomProvider(42L);
            Map<String, Integer> counts = new HashMap<>();
            int picks = 4000;
            for (int i = 0; i < picks; i++) {
                counts.merge(random.pick(provider), 1, Integer::sum);
            }
            int common = counts.getOrDefault("common", 0);
            int rare = counts.getOrDefault("rare", 0);
            assertEquals(picks, common + rare);
            assertTrue(common > 2700 && common < 3300,
                    "expected ~3000 common picks out of 4000 but was " + common);
            assertTrue(rare > 0, "rare entry must remain reachable");
        }

        @Test
        void rejectsNullRandomProvider() {
            WeightedRandom<String> random = WeightedRandom.<String>builder()
                    .add("only", 1)
                    .build();
            assertThrows(NullPointerException.class, () -> random.pick(null));
        }
    }
}
