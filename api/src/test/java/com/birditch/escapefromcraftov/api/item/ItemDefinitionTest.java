package com.birditch.escapefromcraftov.api.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.economy.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ItemDefinitionTest {

    private static final ItemId ID = ItemId.of("healing_potion");

    private static ItemDefinition.Builder builder() {
        return ItemDefinition.builder(ID, "Healing Potion", ItemCategory.POTION,
                ItemRarity.UNCOMMON);
    }

    @Nested
    @DisplayName("builder")
    class BuilderContract {

        @Test
        void appliesDocumentedDefaults() {
            ItemDefinition definition = builder().build();
            assertEquals(ID, definition.id());
            assertEquals("Healing Potion", definition.displayName());
            assertEquals(ItemCategory.POTION, definition.category());
            assertEquals(ItemRarity.UNCOMMON, definition.rarity());
            assertEquals(Money.ZERO, definition.basePrice());
            assertEquals(0, definition.weightGrams());
            assertEquals(1, definition.stackSize());
            assertTrue(definition.tradable());
            assertTrue(definition.sellable());
            assertTrue(definition.droppable());
        }

        @Test
        void overridesEverySetting() {
            ItemDefinition definition = builder()
                    .basePrice(Money.of(2000))
                    .weightGrams(800)
                    .stackSize(4)
                    .tradable(false)
                    .sellable(false)
                    .droppable(false)
                    .build();
            assertEquals(Money.of(2000), definition.basePrice());
            assertEquals(800, definition.weightGrams());
            assertEquals(4, definition.stackSize());
            assertFalse(definition.tradable());
            assertFalse(definition.sellable());
            assertFalse(definition.droppable());
        }

        @Test
        void buildValidatesLikeTheCanonicalConstructor() {
            ItemDefinition.Builder invalid = builder().stackSize(0);
            assertThrows(IllegalArgumentException.class, invalid::build);
        }
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsBlankDisplayName() {
            ItemDefinition.Builder blank =
                    ItemDefinition.builder(ID, "   ", ItemCategory.POTION, ItemRarity.UNCOMMON);
            assertThrows(IllegalArgumentException.class, blank::build);
        }

        @Test
        void rejectsNegativeWeight() {
            ItemDefinition.Builder negative = builder().weightGrams(-1);
            assertThrows(IllegalArgumentException.class, negative::build);
        }

        @Test
        void rejectsStackSizeBelowOne() {
            ItemDefinition.Builder zero = builder().stackSize(0);
            assertThrows(IllegalArgumentException.class, zero::build);
        }

        @Test
        void rejectsNullComponents() {
            assertThrows(NullPointerException.class, () -> new ItemDefinition(
                    null, "Healing Potion", ItemCategory.POTION, ItemRarity.UNCOMMON,
                    Money.ZERO, 0, 1, true, true, true));
            assertThrows(NullPointerException.class, () -> new ItemDefinition(
                    ID, "Healing Potion", ItemCategory.POTION, ItemRarity.UNCOMMON,
                    null, 0, 1, true, true, true));
        }
    }
}
