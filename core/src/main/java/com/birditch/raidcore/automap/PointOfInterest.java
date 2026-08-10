package com.birditch.raidcore.automap;

public record PointOfInterest(
    String id, String name, PoiType type, MapPoint center, int radius, double score) {}
