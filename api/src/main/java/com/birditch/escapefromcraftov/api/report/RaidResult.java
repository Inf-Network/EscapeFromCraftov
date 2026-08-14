package com.birditch.escapefromcraftov.api.report;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Immutable post-raid report. One row is allowed per game and player. */
public record RaidResult(
    UUID resultId,
    CraftovGameId gameId,
    UUID playerId,
    CraftovMapId mapId,
    RaidMode mode,
    RaidDifficulty difficulty,
    RaidOutcome outcome,
    Instant startedAt,
    Instant endedAt,
    /** Total confirmed kills, including hostile players and authored AI. */
    int kills,
    /** Authored AI kills included in {@link #kills}. */
    int pveKills,
    int extractedItemCount,
    long extractedValue,
    long experience,
    long earnings) {

  public RaidResult {
    Preconditions.checkNotNull(resultId, "resultId");
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(mapId, "mapId");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    Preconditions.checkNotNull(outcome, "outcome");
    Preconditions.checkNotNull(startedAt, "startedAt");
    Preconditions.checkNotNull(endedAt, "endedAt");
    Preconditions.checkArgument(!endedAt.isBefore(startedAt),
        "endedAt must not precede startedAt");
    Preconditions.checkArgument(kills >= 0, "kills must not be negative");
    Preconditions.checkArgument(pveKills >= 0 && pveKills <= kills,
        "pveKills must be between 0 and kills");
    Preconditions.checkArgument(extractedItemCount >= 0,
        "extractedItemCount must not be negative");
    Preconditions.checkArgument(extractedValue >= 0, "extractedValue must not be negative");
    Preconditions.checkArgument(experience >= 0, "experience must not be negative");
    Preconditions.checkArgument(earnings >= 0, "earnings must not be negative");
    Preconditions.checkArgument(resultId.equals(idFor(gameId, playerId)),
        "resultId must be the deterministic game/player id");
  }

  /** Source/binary-compatible constructor for reports written before PvE kills were split out. */
  public RaidResult(UUID resultId, CraftovGameId gameId, UUID playerId, CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty, RaidOutcome outcome, Instant startedAt,
      Instant endedAt, int kills, int extractedItemCount, long extractedValue,
      long experience, long earnings) {
    this(resultId, gameId, playerId, mapId, mode, difficulty, outcome, startedAt, endedAt,
        kills, 0, extractedItemCount, extractedValue, experience, earnings);
  }

  public int pvpKills() {
    return kills - pveKills;
  }

  /** Stable idempotency key shared by every settlement path. */
  public static UUID idFor(CraftovGameId gameId, UUID playerId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(playerId, "playerId");
    return UUID.nameUUIDFromBytes(("craftov-raid-result:" + gameId.value() + ":" + playerId)
        .getBytes(StandardCharsets.UTF_8));
  }
}
