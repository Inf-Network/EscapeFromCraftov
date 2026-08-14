package com.birditch.escapefromcraftov.core.database.entity;

import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.report.RaidOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/** Immutable PostgreSQL row for one player's terminal result in one raid. */
@Entity
@Table(name = "craftov_raid_results",
    uniqueConstraints = @UniqueConstraint(name = "uk_craftov_raid_result_game_player",
        columnNames = {"game_id", "player_id"}),
    indexes = @Index(name = "idx_craftov_raid_result_player_time",
        columnList = "player_id,ended_at"))
public class RaidResultEntity {

  @Id
  @Column(name = "result_id", nullable = false, updatable = false)
  private UUID resultId;
  @Column(name = "game_id", nullable = false, updatable = false, length = 96)
  private String gameId;
  @Column(name = "player_id", nullable = false, updatable = false)
  private UUID playerId;
  @Column(name = "map_id", nullable = false, updatable = false, length = 96)
  private String mapId;
  @Enumerated(EnumType.STRING)
  @Column(name = "raid_mode", nullable = false, updatable = false, length = 24)
  private RaidMode mode;
  @Enumerated(EnumType.STRING)
  @Column(name = "difficulty", nullable = false, updatable = false, length = 24)
  private RaidDifficulty difficulty;
  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, updatable = false, length = 24)
  private RaidOutcome outcome;
  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;
  @Column(name = "ended_at", nullable = false, updatable = false)
  private Instant endedAt;
  @Column(name = "kills", nullable = false, updatable = false)
  private int kills;
  @Column(name = "extracted_item_count", nullable = false, updatable = false)
  private int extractedItemCount;
  @Column(name = "extracted_value", nullable = false, updatable = false)
  private long extractedValue;
  @Column(name = "experience", nullable = false, updatable = false)
  private long experience;
  @Column(name = "earnings", nullable = false, updatable = false)
  private long earnings;

  protected RaidResultEntity() {}

  public RaidResultEntity(UUID resultId, String gameId, UUID playerId, String mapId,
      RaidMode mode, RaidDifficulty difficulty, RaidOutcome outcome, Instant startedAt,
      Instant endedAt, int kills, int extractedItemCount, long extractedValue,
      long experience, long earnings) {
    this.resultId = resultId;
    this.gameId = gameId;
    this.playerId = playerId;
    this.mapId = mapId;
    this.mode = mode;
    this.difficulty = difficulty;
    this.outcome = outcome;
    this.startedAt = startedAt;
    this.endedAt = endedAt;
    this.kills = kills;
    this.extractedItemCount = extractedItemCount;
    this.extractedValue = extractedValue;
    this.experience = experience;
    this.earnings = earnings;
  }

  public UUID resultId() { return resultId; }
  public String gameId() { return gameId; }
  public UUID playerId() { return playerId; }
  public String mapId() { return mapId; }
  public RaidMode mode() { return mode; }
  public RaidDifficulty difficulty() { return difficulty; }
  public RaidOutcome outcome() { return outcome; }
  public Instant startedAt() { return startedAt; }
  public Instant endedAt() { return endedAt; }
  public int kills() { return kills; }
  public int extractedItemCount() { return extractedItemCount; }
  public long extractedValue() { return extractedValue; }
  public long experience() { return experience; }
  public long earnings() { return earnings; }
}
