package com.birditch.escapefromcraftov.market;

import com.birditch.escapefromcraftov.api.economy.Money;
import com.birditch.escapefromcraftov.api.item.ItemId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.core.database.entity.MarketListingStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Immutable view of one exact physical item offered on the player market.
public record MarketListing(
    UUID listingId,
    UUID sellerId,
    UUID storageId,
    UUID instanceId,
    ItemId itemId,
    int quantity,
    @Nullable String itemData,
    Money price,
    MarketListingStatus status,
    @Nullable UUID buyerId,
    Instant createdAt,
    Instant expiresAt) {

  public MarketListing {
    Preconditions.checkNotNull(listingId, "listingId");
    Preconditions.checkNotNull(sellerId, "sellerId");
    Preconditions.checkNotNull(storageId, "storageId");
    Preconditions.checkNotNull(instanceId, "instanceId");
    Preconditions.checkNotNull(itemId, "itemId");
    Preconditions.checkArgument(quantity >= 1, "quantity must be positive");
    Preconditions.checkNotNull(price, "price");
    Preconditions.checkNotNull(status, "status");
    Preconditions.checkNotNull(createdAt, "createdAt");
    Preconditions.checkNotNull(expiresAt, "expiresAt");
  }

  public Optional<String> payload() { return Optional.ofNullable(itemData); }
}
