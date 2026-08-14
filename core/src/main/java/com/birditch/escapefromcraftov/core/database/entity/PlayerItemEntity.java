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
    @Index(name = "idx_craftov_items_owner_craftov", columnList = "player_id,craftov_id"),
    // PostgreSQL permits multiple NULLs in a unique index, so pre-migration rows can
    // coexist while every newly persisted physical instance is protected from copying.
    @Index(name = "uk_craftov_items_instance", columnList = "instance_id", unique = true)
})
public class PlayerItemEntity {

  @Id
  @Column(name = "storage_id", nullable = false, updatable = false)
  private UUID storageId;

  @Column(name = "player_id", nullable = false)
  private UUID playerId;

  /// Durable physical-item identity. Nullable only for rows written before this column existed.
  @Column(name = "instance_id")
  private @Nullable UUID instanceId;

  @Column(name = "item_id", nullable = false)
  private String itemId;

  @Column(name = "quantity", nullable = false)
  private int quantity;

  @Enumerated(EnumType.STRING)
  @Column(name = "location", nullable = false)
  private InventoryLocation location;

  @Column(name = "craftov_id")
  private @Nullable String craftovId;

  /// Adapter-owned, versioned Base64 item data. PostgreSQL TEXT avoids truncating rich NBT/meta.
  @Column(name = "item_data", columnDefinition = "text")
  private @Nullable String itemData;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected PlayerItemEntity() {}

  public PlayerItemEntity(UUID storageId, UUID playerId, String itemId, int quantity,
      InventoryLocation location, @Nullable String craftovId, Instant updatedAt) {
    this(storageId, playerId, null, itemId, quantity, location, craftovId, null, updatedAt);
  }

  public PlayerItemEntity(UUID storageId, UUID playerId, @Nullable UUID instanceId,
      String itemId, int quantity, InventoryLocation location, @Nullable String craftovId,
      @Nullable String itemData, Instant updatedAt) {
    this.storageId = storageId;
    this.playerId = playerId;
    this.instanceId = instanceId;
    this.itemId = itemId;
    this.quantity = quantity;
    this.location = location;
    this.craftovId = craftovId;
    this.itemData = itemData;
    this.updatedAt = updatedAt;
  }

  public void moveTo(InventoryLocation target, @Nullable String newCraftovId, Instant now) {
    location = target;
    craftovId = newCraftovId;
    updatedAt = now;
  }

  /// Atomically changes durable ownership without rebuilding the physical item row.
  public void transferTo(UUID newPlayerId, InventoryLocation target, Instant now) {
    this.playerId = newPlayerId;
    moveTo(target, null, now);
  }

  public UUID storageId() { return storageId; }
  public UUID playerId() { return playerId; }
  public @Nullable UUID instanceId() { return instanceId; }
  public UUID instanceIdOrStorageId() { return instanceId == null ? storageId : instanceId; }
  public String itemId() { return itemId; }
  public int quantity() { return quantity; }
  public InventoryLocation location() { return location; }
  public @Nullable String craftovId() { return craftovId; }
  public @Nullable String itemData() { return itemData; }
}
