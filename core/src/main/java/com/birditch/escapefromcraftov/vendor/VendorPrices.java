package com.birditch.escapefromcraftov.vendor;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.item.ItemDefinition;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Set;

/// Stable server-authoritative prices for the military vendor.
public final class VendorPrices {

  public static final Set<Integer> PURCHASE_QUANTITIES = Set.of(1, 8, 16);
  private static final long SELL_PERCENT = 60L;

  private VendorPrices() {}

  public static Money buy(ItemDefinition definition, int quantity) {
    Preconditions.checkNotNull(definition, "definition");
    requirePurchaseQuantity(quantity);
    Preconditions.checkArgument(quantity <= definition.stackSize(),
        "purchase quantity exceeds the registered stack size");
    return Money.of(Math.multiplyExact(definition.basePrice().units(), quantity));
  }

  public static Money sell(ItemDefinition definition, int quantity) {
    Preconditions.checkNotNull(definition, "definition");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    long fullValue = Math.multiplyExact(definition.basePrice().units(), quantity);
    return Money.of(Math.multiplyExact(fullValue, SELL_PERCENT) / 100L);
  }

  public static void requirePurchaseQuantity(int quantity) {
    Preconditions.checkArgument(PURCHASE_QUANTITIES.contains(quantity),
        "purchase quantity must be 1, 8 or 16");
  }
}
