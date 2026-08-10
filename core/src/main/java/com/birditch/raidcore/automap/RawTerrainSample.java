package com.birditch.raidcore.automap;

/// Main-thread world observation consumed later by the pure analysis pipeline.
public record RawTerrainSample(
    int x,
    int y,
    int z,
    boolean safeGround,
    boolean water,
    boolean lava,
    boolean underground,
    boolean existingContainer,
    double constructedRatio,
    double roofCoverage,
    double treeRatio) {}
