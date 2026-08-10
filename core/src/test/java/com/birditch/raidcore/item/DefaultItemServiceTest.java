package com.birditch.raidcore.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.api.RaidException;
import com.birditch.raidcore.api.item.ItemCategory;
import com.birditch.raidcore.api.item.ItemDefinition;
import com.birditch.raidcore.api.item.ItemId;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.item.ItemRarity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DefaultItemService")
class DefaultItemServiceTest {

  private static final ItemId HEALING_POTION = ItemId.of("healing_potion");

  private SimpleItemRegistry registry;
  private DefaultItemService service;

  @BeforeEach
  void setUp() {
    registry = new SimpleItemRegistry();
    registry.register(ItemDefinition
        .builder(HEALING_POTION, "Healing Potion", ItemCategory.POTION, ItemRarity.UNCOMMON)
        .stackSize(4)
        .build());
    service = new DefaultItemService(registry);
  }

  @Test
  @DisplayName("creates instance with fresh id and requested quantity")
  void createsInstanceWithFreshId() {
    ItemInstance first = service.createInstance(HEALING_POTION, 2);
    ItemInstance second = service.createInstance(HEALING_POTION, 2);
    assertEquals(HEALING_POTION, first.itemId());
    assertEquals(2, first.quantity());
    assertNotEquals(first.instanceId(), second.instanceId());
  }

  @Test
  @DisplayName("accepts quantity at the stack limit")
  void acceptsQuantityAtStackLimit() {
    assertEquals(4, service.createInstance(HEALING_POTION, 4).quantity());
  }

  @ParameterizedTest(name = "quantity {0} is rejected")
  @ValueSource(ints = {-1, 0, 5})
  @DisplayName("rejects quantity outside 1..stackSize with RaidException")
  void rejectsQuantityOutsideStackBounds(int quantity) {
    assertThrows(RaidException.class, () -> service.createInstance(HEALING_POTION, quantity));
  }

  @Test
  @DisplayName("unknown item throws RaidException")
  void unknownItemThrows() {
    assertThrows(RaidException.class, () -> service.createInstance(ItemId.of("missing"), 1));
  }

  @Test
  @DisplayName("exposes backing registry")
  void exposesBackingRegistry() {
    assertSame(registry, service.registry());
  }
}
