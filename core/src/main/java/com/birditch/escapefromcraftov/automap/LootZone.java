package com.birditch.escapefromcraftov.automap;

public record LootZone(
    String id,
    MapPoint center,
    int radius,
    LootTier tier,
    String lootTable,
    double density,
    double dangerScore) {}
