package com.birditch.escapefromcraftov.core.database.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/// Immutable audit row for one committed player XP award.
@Entity
@Table(name = "craftov_progression_transactions", indexes = {
    @Index(name = "idx_craftov_progression_player_time", columnList = "player_id,created_at")
})
public class ProgressionTransactionEntity {

  @Id
  @Column(name = "transaction_id", nullable = false, updatable = false)
  private UUID transactionId;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Column(name = "awarded_experience", nullable = false, updatable = false)
  private long awardedExperience;

  @Column(name = "experience_after", nullable = false, updatable = false)
  private long experienceAfter;

  @Column(name = "level_before", nullable = false, updatable = false)
  private int levelBefore;

  @Column(name = "level_after", nullable = false, updatable = false)
  private int levelAfter;

  @Column(name = "reason", nullable = false, updatable = false, length = 200)
  private String reason;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ProgressionTransactionEntity() {}

  public ProgressionTransactionEntity(UUID transactionId, UUID playerId,
      long awardedExperience, long experienceAfter, int levelBefore, int levelAfter,
      String reason, Instant createdAt) {
    this.transactionId = transactionId;
    this.playerId = playerId;
    this.awardedExperience = awardedExperience;
    this.experienceAfter = experienceAfter;
    this.levelBefore = levelBefore;
    this.levelAfter = levelAfter;
    this.reason = reason;
    this.createdAt = createdAt;
  }

  public UUID transactionId() { return transactionId; }
  public UUID playerId() { return playerId; }
  public long awardedExperience() { return awardedExperience; }
  public long experienceAfter() { return experienceAfter; }
  public int levelBefore() { return levelBefore; }
  public int levelAfter() { return levelAfter; }
  public String reason() { return reason; }
  public Instant createdAt() { return createdAt; }
}
