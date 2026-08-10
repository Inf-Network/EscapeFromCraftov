package com.birditch.raidcore.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/// ORM row for long-lived player identity, progression and economy balance.
@Entity
@Table(name = "raid_profiles")
public class PlayerProfileEntity {

  @Id
  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "balance", nullable = false)
  private long balance;

  @Column(name = "level", nullable = false)
  private int level;

  @Column(name = "experience", nullable = false)
  private long experience;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(name = "version")
  private Long version;

  protected PlayerProfileEntity() {}

  public PlayerProfileEntity(
      UUID playerId, String name, long balance, int level, Instant createdAt, Instant updatedAt) {
    this(playerId, name, balance, level, 0L, createdAt, updatedAt);
  }

  public PlayerProfileEntity(UUID playerId, String name, long balance, int level,
      long experience, Instant createdAt, Instant updatedAt) {
    this.playerId = playerId;
    this.name = name;
    this.balance = balance;
    this.level = level;
    this.experience = experience;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void updateIdentity(String name, Instant updatedAt) {
    this.name = name;
    this.updatedAt = updatedAt;
  }

  public void updateBalance(long balance, Instant updatedAt) {
    this.balance = balance;
    this.updatedAt = updatedAt;
  }

  public void updateProgression(long experience, int level, Instant updatedAt) {
    this.experience = experience;
    this.level = level;
    this.updatedAt = updatedAt;
  }

  public UUID playerId() { return playerId; }
  public String name() { return name; }
  public long balance() { return balance; }
  public int level() { return level; }
  public long experience() { return experience; }
  public Instant createdAt() { return createdAt; }
}
