package com.birditch.escapefromcraftov.map;

import java.util.Optional;

/// Common read view used only by editor selection, visualization and indexing.
public sealed interface MapObject permits SpawnDefinition, LootPointDefinition,
    LootZoneDefinition, AiZoneDefinition, ExtractionDefinition, PoiDefinition {

  String id();

  boolean enabled();

  MapObjectType type();

  BlockPosition anchor();

  Optional<CuboidBounds> region();
}
