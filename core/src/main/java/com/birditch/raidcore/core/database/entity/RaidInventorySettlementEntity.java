package com.birditch.raidcore.core.database.entity;

import com.birditch.raidcore.api.inventory.RaidInventoryOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/// Idempotency marker for one player's terminal outcome in one raid.
@Entity
@Table(name = "raid_inventory_settlements", indexes = {
    @Index(name = "idx_raid_settlement_player", columnList = "player_id,created_at")
})
public class RaidInventorySettlementEntity {

  @Id
  @Column(name = "settlement_id", nullable = false, updatable = false, length = 96)
  private String settlementId;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "raid_id", nullable = false, updatable = false)
  private String raidId;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, updatable = false)
  private RaidInventoryOutcome outcome;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected RaidInventorySettlementEntity() {}

  public RaidInventorySettlementEntity(String settlementId, UUID playerId, String raidId,
      RaidInventoryOutcome outcome, Instant createdAt) {
    this.settlementId = settlementId;
    this.playerId = playerId;
    this.raidId = raidId;
    this.outcome = outcome;
    this.createdAt = createdAt;
  }

  public RaidInventoryOutcome outcome() {
    return outcome;
  }
}
