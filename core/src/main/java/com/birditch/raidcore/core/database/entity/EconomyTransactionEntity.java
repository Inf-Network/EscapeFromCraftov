package com.birditch.raidcore.core.database.entity;

import com.birditch.raidcore.api.economy.Currency;
import com.birditch.raidcore.api.economy.EconomyDirection;
import com.birditch.raidcore.api.economy.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/// Immutable audit row for one committed balance mutation.
@Entity
@Table(name = "raid_economy_transactions", indexes = {
    @Index(name = "idx_raid_economy_player_time", columnList = "player_id,created_at")
})
public class EconomyTransactionEntity {

  @Id
  @Column(name = "transaction_id", nullable = false, updatable = false)
  private UUID transactionId;

  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "transaction_type", nullable = false, updatable = false)
  private TransactionType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "direction", nullable = false, updatable = false)
  private EconomyDirection direction;

  @Enumerated(EnumType.STRING)
  @Column(name = "currency", nullable = false, updatable = false)
  private Currency currency;

  @Column(name = "amount", nullable = false, updatable = false)
  private long amount;

  @Column(name = "balance_after", nullable = false, updatable = false)
  private long balanceAfter;

  @Column(name = "description", nullable = false, updatable = false, length = 200)
  private String description;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected EconomyTransactionEntity() {}

  public EconomyTransactionEntity(UUID transactionId, UUID playerId, TransactionType type,
      EconomyDirection direction, Currency currency, long amount, long balanceAfter,
      String description, Instant createdAt) {
    this.transactionId = transactionId;
    this.playerId = playerId;
    this.type = type;
    this.direction = direction;
    this.currency = currency;
    this.amount = amount;
    this.balanceAfter = balanceAfter;
    this.description = description;
    this.createdAt = createdAt;
  }

  public UUID transactionId() { return transactionId; }
  public UUID playerId() { return playerId; }
  public TransactionType type() { return type; }
  public EconomyDirection direction() { return direction; }
  public Currency currency() { return currency; }
  public long amount() { return amount; }
  public long balanceAfter() { return balanceAfter; }
  public String description() { return description; }
  public Instant createdAt() { return createdAt; }
}
