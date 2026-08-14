package com.birditch.escapefromcraftov.vendor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.item.ItemCategory;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.api.item.ItemRarity;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VendorPricesTest {

  private final ItemDefinition item = ItemDefinition.builder(
          ItemId.of("test_item"), "测试物品", ItemCategory.VALUABLE, ItemRarity.RARE)
      .basePrice(Money.of(1_250L))
      .stackSize(64)
      .build();

  @Test
  void supportsClearOneEightAndSixteenPurchaseQuantities() {
    assertEquals(1_250L, VendorPrices.buy(item, 1).units());
    assertEquals(10_000L, VendorPrices.buy(item, 8).units());
    assertEquals(20_000L, VendorPrices.buy(item, 16).units());
    assertThrows(IllegalArgumentException.class, () -> VendorPrices.buy(item, 2));
  }

  @Test
  void cannotBuyManyNonStackableItemsInOneDurableRow() {
    ItemDefinition sword = ItemDefinition.builder(
            ItemId.of("test_sword"), "测试剑", ItemCategory.MELEE_WEAPON, ItemRarity.COMMON)
        .basePrice(Money.of(1_000L))
        .stackSize(1)
        .build();

    assertEquals(1_000L, VendorPrices.buy(sword, 1).units());
    assertThrows(IllegalArgumentException.class, () -> VendorPrices.buy(sword, 8));
    assertThrows(IllegalArgumentException.class, () -> VendorPrices.buy(sword, 16));
  }

  @Test
  void sellsTheWholeStackAtSixtyPercentOfBaseValue() {
    assertEquals(6_000L, VendorPrices.sell(item, 8).units());
  }

  @Test
  void sellAuditPayloadBindsReplayToTheOriginalStorageRow() {
    UUID storageId = UUID.randomUUID();
    String payload = HibernateMilitaryVendorService.sellDescription(
        storageId, item.id(), 8);

    var parsed = HibernateMilitaryVendorService.parseSellDescription(payload, storageId);

    assertEquals(item.id(), parsed.itemId());
    assertEquals(8, parsed.quantity());
    assertThrows(IllegalStateException.class, () ->
        HibernateMilitaryVendorService.parseSellDescription(payload, UUID.randomUUID()));
  }
}
