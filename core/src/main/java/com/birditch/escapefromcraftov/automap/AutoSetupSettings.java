package com.birditch.escapefromcraftov.automap;

import com.birditch.escapefromcraftov.common.check.Preconditions;

/// Tunable, validated settings for scanning and deterministic generation.
public record AutoSetupSettings(
    int defaultRadius,
    int sampleStep,
    int chunksPerTick,
    int maximumSamples,
    SpawnSettings spawns,
    PoiSettings poi,
    LootSettings loot,
    AiSettings ai,
    ExtractionSettings extractions,
    ValidationSettings validation) {

  public AutoSetupSettings {
    Preconditions.checkArgument(defaultRadius >= 64, "defaultRadius must be at least 64");
    Preconditions.checkArgument(sampleStep >= 2, "sampleStep must be at least 2");
    Preconditions.checkArgument(chunksPerTick >= 1, "chunksPerTick must be positive");
    Preconditions.checkArgument(maximumSamples >= 100, "maximumSamples must be at least 100");
    Preconditions.checkNotNull(spawns, "spawns");
    Preconditions.checkNotNull(poi, "poi");
    Preconditions.checkNotNull(loot, "loot");
    Preconditions.checkNotNull(ai, "ai");
    Preconditions.checkNotNull(extractions, "extractions");
    Preconditions.checkNotNull(validation, "validation");
  }

  public static AutoSetupSettings defaults() {
    return new AutoSetupSettings(256, 8, 4, 50_000,
        new SpawnSettings(6, 12, 96, 8),
        new PoiSettings(256, 4, 12),
        new LootSettings(1.0, 8, 200),
        new AiSettings(true, 2, 12),
        new ExtractionSettings(2, 4, 1, 2, 96, 128, 10),
        new ValidationSettings(60, 4, 2, 1));
  }

  public record SpawnSettings(int minimum, int maximum, int minimumDistance, int regionRadius) {
    public SpawnSettings {
      Preconditions.checkArgument(minimum > 0 && maximum >= minimum, "invalid spawn count");
      Preconditions.checkArgument(minimumDistance > 0, "spawn distance must be positive");
      Preconditions.checkArgument(regionRadius > 0, "spawn radius must be positive");
    }
  }

  public record PoiSettings(int minimumArea, int minimum, int maximum) {
    public PoiSettings {
      Preconditions.checkArgument(minimumArea > 0, "POI minimum area must be positive");
      Preconditions.checkArgument(minimum > 0 && maximum >= minimum, "invalid POI count");
    }
  }

  public record LootSettings(double density, int minimumDistance, int maximumPoints) {
    public LootSettings {
      Preconditions.checkArgument(density > 0, "loot density must be positive");
      Preconditions.checkArgument(minimumDistance > 0, "loot distance must be positive");
      Preconditions.checkArgument(maximumPoints > 0, "maximum loot points must be positive");
    }
  }

  public record AiSettings(boolean enabled, int minimumPerRegion, int maximumPerRegion) {
    public AiSettings {
      Preconditions.checkArgument(minimumPerRegion >= 0 && maximumPerRegion >= minimumPerRegion,
          "invalid AI count");
    }
  }

  public record ExtractionSettings(
      int normalMinimum,
      int normalMaximum,
      int conditionalMinimum,
      int conditionalMaximum,
      int minimumDistance,
      int minimumSpawnDistance,
      int regionRadius) {
    public ExtractionSettings {
      Preconditions.checkArgument(normalMinimum > 0 && normalMaximum >= normalMinimum,
          "invalid normal extraction count");
      Preconditions.checkArgument(conditionalMinimum >= 0
          && conditionalMaximum >= conditionalMinimum, "invalid conditional extraction count");
      Preconditions.checkArgument(minimumDistance > 0 && minimumSpawnDistance > 0,
          "extraction distances must be positive");
      Preconditions.checkArgument(regionRadius > 0, "extraction radius must be positive");
    }
  }

  public record ValidationSettings(
      int minimumScore, int minimumSpawns, int minimumExtractions, int minimumLootZones) {
    public ValidationSettings {
      Preconditions.checkArgument(minimumScore >= 0 && minimumScore <= 100,
          "minimum score must be 0..100");
      Preconditions.checkArgument(minimumSpawns > 0 && minimumExtractions > 0
          && minimumLootZones > 0, "validation minimums must be positive");
    }
  }
}
