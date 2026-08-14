package com.birditch.escapefromcraftov.automap;

public record LootContainerPoint(
    String id, MapPoint location, LootTier tier, boolean virtualContainer) {}
