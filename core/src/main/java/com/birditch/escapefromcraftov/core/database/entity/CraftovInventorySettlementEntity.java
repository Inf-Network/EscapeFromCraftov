package com.birditch.escapefromcraftov.core.database.entity;

import com.birditch.escapefromcraftov.api.inventory.CraftovInventoryOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/// Idempotency marker for one player's terminal outcome in one craftov.
@Entity
@Table(name = "craftov_inventory_settlements", indexes = {
    @Index(name = "idx_craftov_settlement_player", columnList = "player_id,created_at")
})
public class CraftovInventorySettlementEntity {

  @Id
  @Column(name = "settlement_id", nullable = false, updatable = false, length = 96)
  private String settlementId;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "craftov_id", nullable = false, updatable = false)
  private String craftovId;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, updatable = false)
  private CraftovInventoryOutcome outcome;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CraftovInventorySettlementEntity() {}

  public CraftovInventorySettlementEntity(String settlementId, UUID playerId, String craftovId,
      CraftovInventoryOutcome outcome, Instant createdAt) {
    this.settlementId = settlementId;
    this.playerId = playerId;
    this.craftovId = craftovId;
    this.outcome = outcome;
    this.createdAt = createdAt;
  }

  public CraftovInventoryOutcome outcome() {
    return outcome;
  }
}
