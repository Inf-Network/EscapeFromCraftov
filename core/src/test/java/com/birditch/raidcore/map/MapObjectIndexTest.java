package com.birditch.raidcore.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MapObjectIndexTest {

  @Test
  void queriesOnlyNearbyVisibleTypesAcrossChunkBoundaries() {
    MapObjectIndex index = new MapObjectIndex(ManualMapFixtures.validMap());

    var nearby = index.nearby(new BlockPosition(250, 64, 250), 24,
        Set.of(MapObjectType.LOOT, MapObjectType.EXTRACTION));

    assertEquals(1, nearby.size());
    assertEquals("loot_a", nearby.getFirst().id());
    assertTrue(index.bucketCount() > 1);
  }

  @Test
  void detectsDistanceToRegionEdgeInsteadOfOnlyItsCenter() {
    MapObjectIndex index = new MapObjectIndex(ManualMapFixtures.validMap());

    var nearby = index.nearby(new BlockPosition(181, 64, 250), 2,
        Set.of(MapObjectType.POI));

    assertEquals("central_factory", nearby.getFirst().id());
  }

  @Test
  void handlesHundredsOfLootPointsWithoutReturningTheWholeMap() {
    ManualRaidMap source = ManualMapFixtures.validMap();
    var loot = new ArrayList<LootPointDefinition>();
    for (int index = 0; index < 500; index++) {
      loot.add(new LootPointDefinition("loot_" + index,
          new BlockPosition(index * 16, 64, 0), "virtual", "default", 1, true, true));
    }
    ManualRaidMap large = new ManualRaidMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), new CuboidBounds(0, 0, 0, 10_000, 100, 500),
        source.revision(), source.enabled(), source.metadata(), source.spawns(), loot, List.of(),
        List.of(), source.extractions(), List.of());

    var nearby = new MapObjectIndex(large).nearby(new BlockPosition(0, 64, 0), 40,
        Set.of(MapObjectType.LOOT));

    assertTrue(nearby.size() < 10);
    assertTrue(nearby.stream().allMatch(object -> object.anchor().x() <= 40));
  }

  @Test
  void returnsNearestRegionEdgesFirstWithStableIdTieBreaking() {
    ManualRaidMap source = ManualMapFixtures.validMap();
    List<LootPointDefinition> loot = List.of(
        new LootPointDefinition("far", new BlockPosition(30, 64, 0), "virtual",
            "default", 1, true, true),
        new LootPointDefinition("near_b", new BlockPosition(2, 64, 0), "virtual",
            "default", 1, true, true),
        new LootPointDefinition("near_a", new BlockPosition(-2, 64, 0), "virtual",
            "default", 1, true, true));
    ManualRaidMap map = new ManualRaidMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(),
        source.enabled(), source.metadata(), source.spawns(), loot, source.lootZones(),
        source.aiZones(), source.extractions(), source.pois());

    List<String> ids = new MapObjectIndex(map).nearby(new BlockPosition(0, 64, 0), 40,
        Set.of(MapObjectType.LOOT)).stream().map(MapObject::id).toList();

    assertEquals(List.of("near_a", "near_b", "far"), ids);
  }
}
