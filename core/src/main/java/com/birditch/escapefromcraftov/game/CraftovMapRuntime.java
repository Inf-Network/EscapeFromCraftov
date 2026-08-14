package com.birditch.escapefromcraftov.game;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPoint;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovAiZone;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.game.CraftovPoi;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.List;

/// Gameplay-facing map data captured atomically before a craftov is published.
public record CraftovMapRuntime(
    long revision,
    List<CraftovSpawnLocation> spawns,
    List<CraftovLootPoint> lootPoints,
    List<CraftovLootZone> lootZones,
    List<ExtractionPoint> extractionPoints,
    List<CraftovExtractionArea> extractionAreas,
    List<CraftovAiZone> aiZones,
    List<CraftovPoi> pois) {

  /** Compatibility constructor for providers that do not expose AI/POI data. */
  public CraftovMapRuntime(long revision, List<CraftovSpawnLocation> spawns,
      List<CraftovLootPoint> lootPoints, List<ExtractionPoint> extractionPoints,
      List<CraftovExtractionArea> extractionAreas) {
    this(revision, spawns, lootPoints, List.of(), extractionPoints, extractionAreas,
        List.of(), List.of());
  }

  /** Compatibility constructor for providers created before Loot Zones became runtime data. */
  public CraftovMapRuntime(long revision, List<CraftovSpawnLocation> spawns,
      List<CraftovLootPoint> lootPoints, List<ExtractionPoint> extractionPoints,
      List<CraftovExtractionArea> extractionAreas, List<CraftovAiZone> aiZones,
      List<CraftovPoi> pois) {
    this(revision, spawns, lootPoints, List.of(), extractionPoints, extractionAreas, aiZones, pois);
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
  }

  public static CraftovMapRuntime development() {
    return new CraftovMapRuntime(0, List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of(), List.of());
  }
}
