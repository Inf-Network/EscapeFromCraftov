package com.birditch.escapefromcraftov.automap;

/// Classified sample used by every generator after Bukkit data is discarded.
public record TerrainSample(
    MapPoint point,
    TerrainType type,
    boolean safe,
    boolean existingContainer,
    double structureScore,
    double opennessScore) {}
