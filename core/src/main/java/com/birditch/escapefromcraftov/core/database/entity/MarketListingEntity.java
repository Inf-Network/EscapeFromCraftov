package com.birditch.escapefromcraftov.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/// Durable listing and immutable snapshot of the exact physical item offered.
@Entity
@Table(name = "craftov_market_listings", indexes = {
    @Index(name = "idx_craftov_market_active_expiry", columnList = "status,expires_at"),
    @Index(name = "idx_craftov_market_seller_status", columnList = "seller_id,status,created_at")
})
public class MarketListingEntity {

  @Id
  @Column(name = "listing_id", nullable = false, updatable = false)
  private UUID listingId;

  @Column(name = "create_operation_id", nullable = false, updatable = false)
  private UUID createOperationId;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(name = "storage_id", nullable = false, updatable = false)
  private UUID storageId;

  @Column(name = "instance_id", nullable = false, updatable = false)
  private UUID instanceId;

  @Column(name = "item_id", nullable = false, updatable = false)
  private String itemId;

  @Column(name = "quantity", nullable = false, updatable = false)
  private int quantity;

  @Column(name = "item_data", columnDefinition = "text", updatable = false)
  private @Nullable String itemData;

  @Column(name = "price", nullable = false, updatable = false)
  private long price;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false)
  private MarketListingStatus status;

  @Column(name = "buyer_id")
  private @Nullable UUID buyerId;

  @Column(name = "close_operation_id")
  private @Nullable UUID closeOperationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "expires_at", nullable = false, updatable = false)
  private Instant expiresAt;

  @Column(name = "closed_at")
  private @Nullable Instant closedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected MarketListingEntity() {}

  public MarketListingEntity(UUID listingId, UUID createOperationId, UUID sellerId,
      UUID storageId, UUID instanceId, String itemId, int quantity,
      @Nullable String itemData, long price, Instant createdAt, Instant expiresAt) {
    this.listingId = listingId;
    this.createOperationId = createOperationId;
    this.sellerId = sellerId;
    this.storageId = storageId;
    this.instanceId = instanceId;
    this.itemId = itemId;
    this.quantity = quantity;
    this.itemData = itemData;
    this.price = price;
    this.status = MarketListingStatus.ACTIVE;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  public void markSold(UUID operationId, UUID buyerId, Instant now) {
    requireActive();
    status = MarketListingStatus.SOLD;
    this.buyerId = buyerId;
    closeOperationId = operationId;
    closedAt = now;
  }

  public void markCancelled(UUID operationId, Instant now) {
    requireActive();
    status = MarketListingStatus.CANCELLED;
    closeOperationId = operationId;
    closedAt = now;
  }

  public void markExpired(Instant now) {
    requireActive();
    status = MarketListingStatus.EXPIRED;
    closedAt = now;
  }

  private void requireActive() {
    if (status != MarketListingStatus.ACTIVE) {
      throw new IllegalStateException("Market listing is already closed: " + listingId);
    }
  }

  public UUID listingId() { return listingId; }
  public UUID createOperationId() { return createOperationId; }
  public UUID sellerId() { return sellerId; }
  public UUID storageId() { return storageId; }
  public UUID instanceId() { return instanceId; }
  public String itemId() { return itemId; }
  public int quantity() { return quantity; }
  public @Nullable String itemData() { return itemData; }
  public long price() { return price; }
  public MarketListingStatus status() { return status; }
  public @Nullable UUID buyerId() { return buyerId; }
  public @Nullable UUID closeOperationId() { return closeOperationId; }
  public Instant createdAt() { return createdAt; }
  public Instant expiresAt() { return expiresAt; }
  public @Nullable Instant closedAt() { return closedAt; }
}
