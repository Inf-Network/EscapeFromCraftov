package com.birditch.escapefromcraftov.game.plugin;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.SplittableRandom;
import org.jspecify.annotations.Nullable;

/** Pure compatibility rules for applying a room's frozen action profile. */
public final class RaidActionRuntimePolicy {

  private RaidActionRuntimePolicy() {}

  /**
   * Resolves the complete raid-wide AI multiplier.
   *
   * <p>Canonical profiles store the complete authored multiplier, rather than
   * an adjustment to the legacy mode+difficulty pair. A missing profile is a
   * rolling-upgrade room and therefore retains the historical calculation.</p>
   */
  public static double aiMultiplier(RaidMode mode, RaidDifficulty difficulty,
      @Nullable RaidActionProfile profile) {
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    return profile == null
        ? mode.aiMultiplier() * difficulty.multiplier()
        : profile.aiMultiplier();
  }

  /**
   * Resolves complete loot-table rolls for one shared loot source.
   *
   * <p>Canonical profiles may author a fractional expectation. The source seed
   * stochastically rounds the fractional part, so every player sees the same
   * shared result while many containers or AI drops converge on the authored
   * expectation. Legacy rooms retain their exact integer roll calculation.</p>
   */
  public static int lootRolls(RaidMode mode, RaidDifficulty difficulty,
      @Nullable RaidActionProfile profile, long sourceSeed) {
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    if (profile == null) {
      return Math.addExact(mode.lootRolls(), difficulty.lootBonusRolls());
    }
    return randomizedRound(profile.lootMultiplier(), sourceSeed);
  }

  /** Stable seed for a room-owned source; never include the opening player. */
  public static long sourceSeed(CraftovGameId gameId, String sourceId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(sourceId, "sourceId");
    Preconditions.checkArgument(!sourceId.isBlank(), "sourceId must not be blank");
    long value = ((long) gameId.value().hashCode() << 32) ^ sourceId.hashCode();
    value ^= 0x9e3779b97f4a7c15L;
    value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
    value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
    return value ^ (value >>> 31);
  }

  static int randomizedRound(double expectation, long seed) {
    Preconditions.checkArgument(Double.isFinite(expectation) && expectation > 0,
        "loot expectation must be finite and positive");
    Preconditions.checkArgument(expectation <= RaidActionProfile.MAX_LOOT_MULTIPLIER,
        "loot expectation exceeds safe runtime limit");
    int whole = (int) Math.floor(expectation);
    double fraction = expectation - whole;
    if (fraction == 0 || whole == Integer.MAX_VALUE) return whole;
    return new SplittableRandom(seed).nextDouble() < fraction ? whole + 1 : whole;
  }
}
