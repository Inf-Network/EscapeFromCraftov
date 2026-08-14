package com.birditch.escapefromcraftov.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/// ORM row for a player's currently equipped lobby cosmetic.
@Entity
@Table(name = "craftov_player_cosmetics")
public class PlayerCosmeticEntity {

  @Id
  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "cosmetic_id", nullable = false)
  private String cosmeticId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected PlayerCosmeticEntity() {}

  public PlayerCosmeticEntity(UUID playerId, String cosmeticId, Instant updatedAt) {
    this.playerId = playerId;
    this.cosmeticId = cosmeticId;
    this.updatedAt = updatedAt;
  }

  public void update(String cosmeticId, Instant updatedAt) {
    this.cosmeticId = cosmeticId;
    this.updatedAt = updatedAt;
  }

  public String cosmeticId() { return cosmeticId; }
}
