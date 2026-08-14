package com.birditch.escapefromcraftov.automap;

public record AiSpawnRegion(
    String id, MapPoint center, int radius, int minimumMobs, int maximumMobs,
    double dangerScore) {}
