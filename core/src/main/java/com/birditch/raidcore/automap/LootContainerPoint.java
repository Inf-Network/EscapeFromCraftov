package com.birditch.raidcore.automap;

public record LootContainerPoint(
    String id, MapPoint location, LootTier tier, boolean virtualContainer) {}
