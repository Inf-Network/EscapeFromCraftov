package com.birditch.escapefromcraftov.automap;

import org.jspecify.annotations.Nullable;

public record ExtractionRegion(
    String id,
    String name,
    MapPoint center,
    int radius,
    AutoExtractionType type,
    @Nullable String conditionValue) {}
