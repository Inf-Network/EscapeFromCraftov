package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.UUID;

public record MarketTrade(
    UUID operationId,
    MarketListing listing,
    UUID buyerId,
    Money buyerBalanceAfter,
    Money sellerBalanceAfter,
    boolean replayed) {

  public MarketTrade {
    Preconditions.checkNotNull(operationId, "operationId");
    Preconditions.checkNotNull(listing, "listing");
    Preconditions.checkNotNull(buyerId, "buyerId");
    Preconditions.checkNotNull(buyerBalanceAfter, "buyerBalanceAfter");
    Preconditions.checkNotNull(sellerBalanceAfter, "sellerBalanceAfter");
  }
}
