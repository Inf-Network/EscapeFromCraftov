package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;

/// One map-authored action tier. Economic gates remain descriptive in this
/// phase; matchmaking can carry the canonical id while null-tier requests keep
/// using the legacy mode/difficulty pair.
public record RaidActionProfile(
    RaidTierId id,
    String displayName,
    long minimumLoadoutValue,
    double aiMultiplier,
    double lootMultiplier,
    long entryFee,
    List<LegacyAlias> legacyAliases) {

  /** Safety ceiling preventing authored profiles from overwhelming the main thread. */
  public static final double MAX_AI_MULTIPLIER = 8.0;
  /** Maximum complete loot-table rolls contributed by one shared source. */
  public static final double MAX_LOOT_MULTIPLIER = 16.0;

  public RaidActionProfile {
    Preconditions.checkNotNull(id, "id");
    Preconditions.checkNotNull(displayName, "displayName");
    Preconditions.checkArgument(!displayName.isBlank(), "displayName must not be blank");
    Preconditions.checkArgument(minimumLoadoutValue >= 0,
        "minimumLoadoutValue must not be negative");
    Preconditions.checkArgument(Double.isFinite(aiMultiplier) && aiMultiplier > 0
            && aiMultiplier <= MAX_AI_MULTIPLIER,
        "aiMultiplier must be finite and in (0, " + MAX_AI_MULTIPLIER + "]");
    Preconditions.checkArgument(Double.isFinite(lootMultiplier) && lootMultiplier > 0
            && lootMultiplier <= MAX_LOOT_MULTIPLIER,
        "lootMultiplier must be finite and in (0, " + MAX_LOOT_MULTIPLIER + "]");
    Preconditions.checkArgument(entryFee >= 0, "entryFee must not be negative");
    legacyAliases = List.copyOf(Preconditions.checkNotNull(legacyAliases, "legacyAliases"));
    Preconditions.checkArgument(new HashSet<>(legacyAliases).size() == legacyAliases.size(),
        "legacyAliases must not contain duplicates");
  }

  /// Exact legacy selection that may address this canonical action tier during
  /// the rolling migration from mode+difficulty to tier ids.
  public record LegacyAlias(RaidMode mode, RaidDifficulty difficulty) {

    public LegacyAlias {
      Preconditions.checkNotNull(mode, "mode");
      Preconditions.checkNotNull(difficulty, "difficulty");
    }
  }
}
