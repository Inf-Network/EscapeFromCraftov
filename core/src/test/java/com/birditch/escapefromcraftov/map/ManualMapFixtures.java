package com.birditch.escapefromcraftov.map;

import java.util.List;
import java.util.Map;
import java.util.Set;

final class ManualMapFixtures {

  static final CuboidBounds BOUNDS = new CuboidBounds(0, 0, 0, 500, 100, 500);

  private ManualMapFixtures() {}

  static ManualCraftovMap validMap() {
    return new ManualCraftovMap(ManualCraftovMap.SCHEMA_VERSION, "factory", "Factory", "world",
        BOUNDS, 4, true, Map.of("author", "test"),
        List.of(spawn("spawn_a", 20, 20), spawn("spawn_b", 480, 20)),
        List.of(new LootPointDefinition("loot_a", new BlockPosition(250, 64, 250),
            "virtual", "default", 2, true, true)),
        List.of(new LootZoneDefinition("loot_zone_a",
            new CuboidBounds(200, 50, 200, 300, 80, 300), "default", 2, 1, 0.5, true)),
        List.of(new AiZoneDefinition("ai_zone_a",
            new CuboidBounds(210, 50, 210, 290, 80, 290), "scav", 2, 4, 60, 1,
            "scav", true)),
        List.of(extraction("north_gate", 10, 450, ManualExtractionType.NORMAL, null),
            extraction("south_gate", 450, 450, ManualExtractionType.TIMED, "600")),
        List.of(new PoiDefinition("central_factory", "Central Factory",
            new CuboidBounds(180, 40, 180, 320, 90, 320), 3, true)))
        .withEvents(List.of(new MapEventDefinition("factory_overseer", "Factory Overseer",
            MapEventType.HIGH_VALUE_TARGET, "ai_zone_a", "loot_a", 15, 4, 1.5,
            30, true)));
  }

  static ManualMapReferences references() {
    return new ManualMapReferences(true, Set.of("default"), Set.of("extract_key"));
  }

  static SpawnDefinition spawn(String id, int x, int z) {
    return new SpawnDefinition(id, new BlockPosition(x, 64, z), 0, 0, "players", 1, true,
        null);
  }

  static ExtractionDefinition extraction(String id, int x, int z, ManualExtractionType type,
      String condition) {
    return new ExtractionDefinition(id, id, new CuboidBounds(x, 60, z, x + 8, 70, z + 8),
        type, 10, condition, true);
  }
}
