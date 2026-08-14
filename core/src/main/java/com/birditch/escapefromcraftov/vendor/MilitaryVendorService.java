package com.birditch.escapefromcraftov.vendor;

import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.result.Result;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/// Durable military-vendor operations. Implementations keep balance and item
/// ownership in one transaction and treat {@code operationId} as an idempotency key.
public interface MilitaryVendorService {

  String PROFILE_NOT_FOUND = "PROFILE_NOT_FOUND";
  String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
  String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
  String ITEM_NOT_IN_WAREHOUSE = "ITEM_NOT_IN_WAREHOUSE";
  String ITEM_NOT_TRADABLE = "ITEM_NOT_TRADABLE";

  CompletableFuture<Result<VendorTrade>> buy(
      UUID operationId, UUID playerId, ItemId itemId, int quantity);

  CompletableFuture<Result<VendorTrade>> sell(
      UUID operationId, UUID playerId, UUID storageId);
}
