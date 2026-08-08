package com.birditch.raidcore.loot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.api.event.loot.LootGeneratedEvent;
import com.birditch.raidcore.api.item.ItemInstance;
import com.birditch.raidcore.api.loot.LootEntry;
import com.birditch.raidcore.api.loot.LootException;
import com.birditch.raidcore.api.loot.LootPool;
import com.birditch.raidcore.api.loot.LootRollContext;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.common.random.SeededRandomProvider;
import com.birditch.raidcore.core.event.SimpleEventBus;
import com.birditch.raidcore.item.DefaultItemService;
import com.birditch.raidcore.item.DefaultItems;
import com.birditch.raidcore.item.SimpleItemRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DefaultLootService")
class DefaultLootServiceTest {

  private SimpleLootTableRegistry tables;
  private SimpleEventBus eventBus;
  private DefaultLootService service;

  @BeforeEach
  void setUp() {
    SimpleItemRegistry itemRegistry = new SimpleItemRegistry();
    DefaultItems.registerAll(itemRegistry);
    tables = new SimpleLootTableRegistry();
    DebugLootTables.registerAll(tables);
    eventBus = new SimpleEventBus(Logger.getLogger(DefaultLootServiceTest.class.getName()));
    service = new DefaultLootService(
        tables, new DefaultItemService(itemRegistry), new SeededRandomProvider(42L), eventBus);
  }

  @Nested
  @DisplayName("roll")
  class Roll {

    @ParameterizedTest(name = "{0} yields one stack per pool roll")
    @ValueSource(strings = {"debug_common", "debug_military"})
    void yieldsOneStackPerPoolRoll(String tableName) {
      List<ItemInstance> result = service.roll(LootTableId.of(tableName), LootRollContext.empty());

      assertEquals(3, result.size());
    }

    @ParameterizedTest(name = "{0} stacks match pool entries and stay within quantity bounds")
    @ValueSource(strings = {"debug_common", "debug_military"})
    void quantitiesStayWithinEntryBounds(String tableName) {
      LootTableId tableId = LootTableId.of(tableName);
      LootPool pool = tables.require(tableId).pools().getFirst();

      for (int i = 0; i < 200; i++) {
        for (ItemInstance stack : service.roll(tableId, LootRollContext.empty())) {
          LootEntry entry = entryFor(pool, stack);
          assertTrue(stack.quantity() >= entry.minQuantity(),
              () -> "Quantity below minimum for " + stack.itemId().value() + ": " + stack.quantity());
          assertTrue(stack.quantity() <= entry.maxQuantity(),
              () -> "Quantity above maximum for " + stack.itemId().value() + ": " + stack.quantity());
        }
      }
    }

    @Test
    @DisplayName("returns an immutable list")
    void returnsImmutableList() {
      List<ItemInstance> result =
          service.roll(LootTableId.of("debug_common"), LootRollContext.empty());

      assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    @DisplayName("unknown table throws LootException")
    void unknownTableThrows() {
      LootTableId unknown = LootTableId.of("no_such_table");
      LootRollContext context = LootRollContext.empty();

      assertThrows(LootException.class, () -> service.roll(unknown, context));
    }
  }

  @Nested
  @DisplayName("events")
  class Events {

    @Test
    @DisplayName("publishes LootGeneratedEvent carrying the rolled items")
    void publishesLootGeneratedEvent() {
      List<LootGeneratedEvent> published = new ArrayList<>();
      eventBus.subscribe(LootGeneratedEvent.class, published::add);
      LootTableId tableId = LootTableId.of("debug_military");

      List<ItemInstance> result =
          service.roll(tableId, LootRollContext.forPlayer(UUID.randomUUID()));

      assertEquals(1, published.size());
      LootGeneratedEvent event = published.getFirst();
      assertEquals(tableId, event.tableId());
      assertEquals(result, event.items());
    }

    @Test
    @DisplayName("publishes nothing when the table is unknown")
    void publishesNothingForUnknownTable() {
      List<LootGeneratedEvent> published = new ArrayList<>();
      eventBus.subscribe(LootGeneratedEvent.class, published::add);
      LootTableId unknown = LootTableId.of("no_such_table");
      LootRollContext context = LootRollContext.empty();

      assertThrows(LootException.class, () -> service.roll(unknown, context));
      assertTrue(published.isEmpty());
    }
  }

  private static LootEntry entryFor(LootPool pool, ItemInstance stack) {
    return pool.entries().stream()
        .filter(entry -> entry.itemId().equals(stack.itemId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError(
            "Rolled item is not a pool entry: " + stack.itemId().value()));
  }
}
