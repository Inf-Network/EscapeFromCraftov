package com.birditch.escapefromcraftov.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/// ORM row for one persistent player preference.
@Entity
@Table(name = "craftov_player_settings")
public class PlayerSettingEntity {

  @EmbeddedId
  private PlayerSettingId id;

  @Column(name = "enabled", nullable = false)
  private boolean enabled;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected PlayerSettingEntity() {}

  public PlayerSettingEntity(PlayerSettingId id, boolean enabled, Instant updatedAt) {
    this.id = id;
    this.enabled = enabled;
    this.updatedAt = updatedAt;
  }

  public void update(boolean enabled, Instant updatedAt) {
    this.enabled = enabled;
    this.updatedAt = updatedAt;
  }

  public PlayerSettingId id() { return id; }
  public boolean enabled() { return enabled; }
}
