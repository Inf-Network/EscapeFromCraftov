package com.birditch.escapefromcraftov.core.database.entity;

import com.birditch.escapefromcraftov.api.inventory.InventoryLocation;
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

/// ORM row for one stack owned by a player.
@Entity
@Table(name = "craftov_player_items", indexes = {
    @Index(name = "idx_craftov_items_owner_location", columnList = "player_id,location"),
    @Index(name = "idx_craftov_items_owner_craftov", columnList = "player_id,craftov_id")
})
public class PlayerItemEntity {

  @Id
  @Column(name = "storage_id", nullable = false, updatable = false)
  private UUID storageId;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "item_id", nullable = false)
  private String itemId;

  @Column(name = "quantity", nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "location", nullable = false)
  private InventoryLocation location;

  @Column(name = "craftov_id")
  private @Nullable String craftovId;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected PlayerItemEntity() {}

  public PlayerItemEntity(UUID storageId, UUID playerId, String itemId, int quantity,
      InventoryLocation location, @Nullable String craftovId, Instant updatedAt) {
    this.storageId = storageId;
    this.playerId = playerId;
    this.itemId = itemId;
    this.quantity = quantity;
    this.location = location;
    this.craftovId = craftovId;
    this.updatedAt = updatedAt;
  }

  public void moveTo(InventoryLocation target, @Nullable String newCraftovId, Instant now) {
    location = target;
    craftovId = newCraftovId;
    updatedAt = now;
  }

  public UUID storageId() { return storageId; }
  public UUID playerId() { return playerId; }
  public String itemId() { return itemId; }
  public int quantity() { return quantity; }
  public InventoryLocation location() { return location; }
  public @Nullable String craftovId() { return craftovId; }
}
