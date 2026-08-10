package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;

public record ManualMapValidationSettings(
    int minimumSpawns,
    int minimumExtractions,
    int minimumLootZones,
    int minimumAiZones,
    int minimumPois,
    double minimumSpawnDistance,
    double minimumSpawnExtractionDistance) {

  public ManualMapValidationSettings {
    Preconditions.checkArgument(minimumSpawns >= 1, "minimumSpawns must be positive");
    Preconditions.checkArgument(minimumExtractions >= 1,
        "minimumExtractions must be positive");
    Preconditions.checkArgument(minimumLootZones >= 0,
        "minimumLootZones must not be negative");
    Preconditions.checkArgument(minimumAiZones >= 0,
        "minimumAiZones must not be negative");
    Preconditions.checkArgument(minimumPois >= 0,
        "minimumPois must not be negative");
    Preconditions.checkArgument(Double.isFinite(minimumSpawnDistance)
        && minimumSpawnDistance >= 0, "minimumSpawnDistance must be finite and non-negative");
    Preconditions.checkArgument(Double.isFinite(minimumSpawnExtractionDistance)
        && minimumSpawnExtractionDistance >= 0,
        "minimumSpawnExtractionDistance must be finite and non-negative");
  }

  public static ManualMapValidationSettings defaults() {
    return new ManualMapValidationSettings(2, 2, 1, 1, 1, 32, 64);
  }
}
