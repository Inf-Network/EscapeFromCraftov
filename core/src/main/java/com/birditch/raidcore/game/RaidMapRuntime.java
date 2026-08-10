package com.birditch.raidcore.game;

import com.birditch.raidcore.api.extraction.ExtractionPoint;
import com.birditch.raidcore.api.game.RaidExtractionArea;
import com.birditch.raidcore.api.game.RaidAiZone;
import com.birditch.raidcore.api.game.RaidLootPoint;
import com.birditch.raidcore.api.game.RaidPoi;
import com.birditch.raidcore.api.game.RaidSpawnLocation;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// Gameplay-facing map data captured atomically before a raid is published.
public record RaidMapRuntime(
    long revision,
    List<RaidSpawnLocation> spawns,
    List<RaidLootPoint> lootPoints,
    List<ExtractionPoint> extractionPoints,
    List<RaidExtractionArea> extractionAreas,
    List<RaidAiZone> aiZones,
    List<RaidPoi> pois) {

  /** Compatibility constructor for providers that do not expose AI/POI data. */
  public RaidMapRuntime(long revision, List<RaidSpawnLocation> spawns,
      List<RaidLootPoint> lootPoints, List<ExtractionPoint> extractionPoints,
      List<RaidExtractionArea> extractionAreas) {
    this(revision, spawns, lootPoints, extractionPoints, extractionAreas, List.of(), List.of());
  }

  public RaidMapRuntime {
    Preconditions.checkArgument(revision >= 0, "revision must not be negative");
    spawns = List.copyOf(Preconditions.checkNotNull(spawns, "spawns"));
    lootPoints = List.copyOf(Preconditions.checkNotNull(lootPoints, "lootPoints"));
    extractionPoints = List.copyOf(Preconditions.checkNotNull(extractionPoints,
        "extractionPoints"));
    extractionAreas = List.copyOf(Preconditions.checkNotNull(extractionAreas,
        "extractionAreas"));
    aiZones = List.copyOf(Preconditions.checkNotNull(aiZones, "aiZones"));
    pois = List.copyOf(Preconditions.checkNotNull(pois, "pois"));
  }

  public static RaidMapRuntime development() {
    return new RaidMapRuntime(0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}
