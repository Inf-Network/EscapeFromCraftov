package com.birditch.raidcore.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/// Composite ORM identifier for one setting owned by one player.
@Embeddable
public class PlayerSettingId implements Serializable {

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "setting_key", nullable = false, updatable = false)
  private String settingKey;

  protected PlayerSettingId() {}

  public PlayerSettingId(UUID playerId, String settingKey) {
    this.playerId = playerId;
    this.settingKey = settingKey;
  }

  public UUID playerId() { return playerId; }
  public String settingKey() { return settingKey; }

  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof PlayerSettingId that
        && Objects.equals(playerId, that.playerId)
        && Objects.equals(settingKey, that.settingKey);
  }

  @Override
  public int hashCode() { return Objects.hash(playerId, settingKey); }
}
