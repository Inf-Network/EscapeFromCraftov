package com.birditch.raidcore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.item.ItemCategory;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemRarity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SimpleItemRegistry")
class SimpleItemRegistryTest {

  private SimpleItemRegistry registry;

  @BeforeEach
  void setUp() {
    registry = new SimpleItemRegistry();
  }

  private static ItemDefinition definition(String id) {
    return ItemDefinition
        .builder(ItemId.of(id), "Item " + id, ItemCategory.MISC, ItemRarity.COMMON)
        .build();
  }

  @Nested
  @DisplayName("register")
  class Register {

    @Test
    @DisplayName("registered definition is findable by id")
    void registeredDefinitionIsFindable() {
      ItemDefinition knife = definition("knife");
      registry.register(knife);
      assertSame(knife, registry.find(ItemId.of("knife")).orElseThrow());
    }

    @Test
    @DisplayName("duplicate id is rejected with RaidException")
    void duplicateIdRejected() {
      registry.register(definition("knife"));
      RaidException exception =
          assertThrows(RaidException.class, () -> registry.register(definition("knife")));
      assertEquals("Item already registered: knife", exception.getMessage());
    }
  }

  @Nested
  @DisplayName("lookup")
  class Lookup {

    @Test
    @DisplayName("find returns empty for unknown id")
    void findUnknownReturnsEmpty() {
      assertTrue(registry.find(ItemId.of("missing")).isEmpty());
    }

    @Test
    @DisplayName("require returns registered definition")
    void requireReturnsRegistered() {
      ItemDefinition knife = definition("knife");
      registry.register(knife);
      assertSame(knife, registry.require(ItemId.of("knife")));
    }

    @Test
    @DisplayName("require throws RaidException for unknown id")
    void requireUnknownThrows() {
      assertThrows(RaidException.class, () -> registry.require(ItemId.of("missing")));
    }
  }

  @Nested
  @DisplayName("all and size")
  class AllAndSize {

    @Test
    @DisplayName("all preserves registration order")
    void allPreservesRegistrationOrder() {
      registry.register(definition("zulu"));
      registry.register(definition("alpha"));
      registry.register(definition("mike"));
      List<ItemId> ids = registry.all().stream().map(ItemDefinition::id).toList();
      assertEquals(List.of(ItemId.of("zulu"), ItemId.of("alpha"), ItemId.of("mike")), ids);
    }

    @Test
    @DisplayName("all is unmodifiable")
    void allIsUnmodifiable() {
      registry.register(definition("knife"));
      assertThrows(UnsupportedOperationException.class, () -> registry.all().clear());
    }

    @Test
    @DisplayName("size tracks registrations")
    void sizeTracksRegistrations() {
      assertEquals(0, registry.size());
      registry.register(definition("knife"));
      registry.register(definition("rope"));
      assertEquals(2, registry.size());
    }
  }
}
