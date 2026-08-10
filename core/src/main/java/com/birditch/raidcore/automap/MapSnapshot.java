package com.birditch.raidcore.automap;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.List;

/// Immutable, finite world snapshot safe to process away from Paper's thread.
public record MapSnapshot(
    String worldName,
    long worldSeed,
    int seaLevel,
    int sampleStep,
    MapBounds bounds,
    List<RawTerrainSample> samples) {

  public MapSnapshot {
    Preconditions.checkNotNull(worldName, "worldName");
    Preconditions.checkNotNull(bounds, "bounds");
    Preconditions.checkNotNull(samples, "samples");
    Preconditions.checkArgument(!worldName.isBlank(), "worldName must not be blank");
    Preconditions.checkArgument(sampleStep > 0, "sampleStep must be positive");
    samples = List.copyOf(samples);
  }
}
