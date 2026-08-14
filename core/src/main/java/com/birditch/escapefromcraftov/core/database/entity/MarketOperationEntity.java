package com.birditch.escapefromcraftov.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/// Immutable global idempotency record for a committed market mutation.
@Entity
@Table(name = "craftov_market_operations", indexes = {
    @Index(name = "idx_craftov_market_operation_listing", columnList = "listing_id")
})
public class MarketOperationEntity {

  @Id
  @Column(name = "operation_id", nullable = false, updatable = false)
  private UUID operationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation_type", nullable = false, updatable = false)
  private MarketOperationType type;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "listing_id", nullable = false, updatable = false)
  private UUID listingId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected MarketOperationEntity() {}

  public MarketOperationEntity(UUID operationId, MarketOperationType type,
      UUID playerId, UUID listingId, Instant createdAt) {
    this.operationId = operationId;
    this.type = type;
    this.playerId = playerId;
    this.listingId = listingId;
    this.createdAt = createdAt;
  }

  public UUID operationId() { return operationId; }
  public MarketOperationType type() { return type; }
  public UUID playerId() { return playerId; }
  public UUID listingId() { return listingId; }
}
