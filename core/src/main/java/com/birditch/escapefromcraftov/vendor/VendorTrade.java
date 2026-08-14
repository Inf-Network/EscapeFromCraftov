package com.birditch.escapefromcraftov.vendor;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

/// One committed military-vendor transaction.
public record VendorTrade(
    UUID operationId,
    VendorTradeType type,
    ItemId itemId,
    int quantity,
    Money total,
    Money balanceAfter,
    boolean replayed) {

  public VendorTrade {
    Preconditions.checkNotNull(operationId, "operationId");
    Preconditions.checkNotNull(type, "type");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    Preconditions.checkNotNull(total, "total");
    Preconditions.checkNotNull(balanceAfter, "balanceAfter");
  }
}
