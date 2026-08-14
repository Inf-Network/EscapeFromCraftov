package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/// Gameplay-facing map data captured atomically before a craftov is published.
public record CraftovMapRuntime(
    long revision,
    List<CraftovSpawnLocation> spawns,
    List<CraftovLootPoint> lootPoints,
    List<CraftovLootZone> lootZones,
    List<ExtractionPoint> extractionPoints,
    List<CraftovExtractionArea> extractionAreas,
    List<CraftovAiZone> aiZones,
    List<CraftovPoi> pois,
    List<RaidActionProfile> actionTiers) {

  /** Compatibility constructor for providers that do not expose AI/POI data. */
  public CraftovMapRuntime(long revision, List<CraftovSpawnLocation> spawns,
      List<CraftovLootPoint> lootPoints, List<ExtractionPoint> extractionPoints,
      List<CraftovExtractionArea> extractionAreas) {
    this(revision, spawns, lootPoints, List.of(), extractionPoints, extractionAreas,
        List.of(), List.of(), LegacyRaidTierResolver.defaultProfiles());
  }

  /** Compatibility constructor for providers created before Loot Zones became runtime data. */
  public CraftovMapRuntime(long revision, List<CraftovSpawnLocation> spawns,
      List<CraftovLootPoint> lootPoints, List<ExtractionPoint> extractionPoints,
      List<CraftovExtractionArea> extractionAreas, List<CraftovAiZone> aiZones,
      List<CraftovPoi> pois) {
    this(revision, spawns, lootPoints, List.of(), extractionPoints, extractionAreas, aiZones, pois);
  }

  /** Compatibility constructor for runtimes captured before action tiers. */
  public CraftovMapRuntime(long revision, List<CraftovSpawnLocation> spawns,
      List<CraftovLootPoint> lootPoints, List<CraftovLootZone> lootZones,
      List<ExtractionPoint> extractionPoints, List<CraftovExtractionArea> extractionAreas,
      List<CraftovAiZone> aiZones, List<CraftovPoi> pois) {
    this(revision, spawns, lootPoints, lootZones, extractionPoints, extractionAreas, aiZones, pois,
        LegacyRaidTierResolver.defaultProfiles());
  }

  public CraftovMapRuntime {
    Preconditions.checkArgument(revision >= 0, "revision must not be negative");
    spawns = List.copyOf(Preconditions.checkNotNull(spawns, "spawns"));
    lootPoints = List.copyOf(Preconditions.checkNotNull(lootPoints, "lootPoints"));
    lootZones = List.copyOf(Preconditions.checkNotNull(lootZones, "lootZones"));
    extractionPoints = List.copyOf(Preconditions.checkNotNull(extractionPoints,
        "extractionPoints"));
    extractionAreas = List.copyOf(Preconditions.checkNotNull(extractionAreas,
        "extractionAreas"));
    aiZones = List.copyOf(Preconditions.checkNotNull(aiZones, "aiZones"));
    pois = List.copyOf(Preconditions.checkNotNull(pois, "pois"));
    actionTiers = List.copyOf(Preconditions.checkNotNull(actionTiers, "actionTiers"));
    Preconditions.checkArgument(!actionTiers.isEmpty(), "actionTiers must not be empty");
    HashSet<RaidTierId> tierIds = new HashSet<>();
    for (RaidActionProfile profile : actionTiers) {
      Preconditions.checkArgument(tierIds.add(profile.id()),
          "actionTiers must not contain duplicate id: " + profile.id().value());
    }
  }

  public Optional<RaidActionProfile> actionTier(RaidTierId id) {
    Preconditions.checkNotNull(id, "id");
    return actionTiers.stream().filter(profile -> profile.id().equals(id)).findFirst();
  }

  public static CraftovMapRuntime development() {
    return new CraftovMapRuntime(0, List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of(), LegacyRaidTierResolver.defaultProfiles());
  }
}
