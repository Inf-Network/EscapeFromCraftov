package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.common.result.Result;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Cross-lobby durable market. All mutations use a global idempotency key.
public interface PlayerMarketService {

  String PROFILE_NOT_FOUND = "PROFILE_NOT_FOUND";
  String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
  String ITEM_NOT_IN_WAREHOUSE = "ITEM_NOT_IN_WAREHOUSE";
  String LISTING_NOT_FOUND = "LISTING_NOT_FOUND";
  String LISTING_UNAVAILABLE = "LISTING_UNAVAILABLE";
  String NOT_LISTING_OWNER = "NOT_LISTING_OWNER";
  String CANNOT_BUY_OWN = "CANNOT_BUY_OWN";
  String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

  CompletableFuture<Result<MarketMutation>> listItem(UUID operationId, UUID listingId,
      UUID sellerId, UUID storageId, Money price, Duration lifetime);

  CompletableFuture<Result<MarketTrade>> buy(
      UUID operationId, UUID buyerId, UUID listingId);

  CompletableFuture<Result<MarketMutation>> cancel(
      UUID operationId, UUID sellerId, UUID listingId);

  CompletableFuture<MarketPage> browse(int requestedPage, int pageSize);

  CompletableFuture<MarketPage> mine(UUID sellerId, int requestedPage, int pageSize);

  /// Reclaims one bounded batch of expired listings and returns their exact items.
  CompletableFuture<Integer> expireDue();
}
