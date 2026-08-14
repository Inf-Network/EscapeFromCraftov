package com.birditch.escapefromcraftov.api.game;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;
import java.util.Optional;

/// Compatibility bridge between the old mode+difficulty selection and the
/// canonical map-authored action tiers.
///
/// <p>An unmatched legacy pair deliberately remains unmatched: callers must
/// continue using the original pair when a rolling-upgrade payload has no
/// tier id, preserving all six historical combinations exactly.</p>
public final class LegacyRaidTierResolver {

  private static final List<RaidActionProfile> DEFAULT_PROFILES = List.of(
      profile("normal", "常规行动", RaidMode.NORMAL, RaidDifficulty.NORMAL),
      profile("confidential", "机密行动", RaidMode.CONFIDENTIAL, RaidDifficulty.NORMAL),
      profile("top_secret", "绝密行动", RaidMode.CONFIDENTIAL, RaidDifficulty.HARD));

  private LegacyRaidTierResolver() {}

  public static List<RaidActionProfile> defaultProfiles() {
    return DEFAULT_PROFILES;
  }

  public static Optional<RaidActionProfile> resolve(
      List<RaidActionProfile> profiles, RaidMode mode, RaidDifficulty difficulty) {
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(mode, "mode");
    Preconditions.checkNotNull(difficulty, "difficulty");
    RaidActionProfile.LegacyAlias requested =
        new RaidActionProfile.LegacyAlias(mode, difficulty);
    return profiles.stream()
        .filter(profile -> profile.legacyAliases().contains(requested))
        .findFirst();
  }

  public static Optional<RaidActionProfile> resolve(
      List<RaidActionProfile> profiles, RaidTierId tierId) {
    Preconditions.checkNotNull(profiles, "profiles");
    Preconditions.checkNotNull(tierId, "tierId");
    return profiles.stream().filter(profile -> profile.id().equals(tierId)).findFirst();
  }

  private static RaidActionProfile profile(String id, String displayName,
      RaidMode mode, RaidDifficulty difficulty) {
    return new RaidActionProfile(RaidTierId.of(id), displayName, 0,
        mode.aiMultiplier() * difficulty.multiplier(),
        mode.lootRolls() + difficulty.lootBonusRolls(), 0,
        List.of(new RaidActionProfile.LegacyAlias(mode, difficulty)));
  }
}
