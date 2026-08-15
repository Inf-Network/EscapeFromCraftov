package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManualMapEditorBatchTest {

  private final ManualMapEditor editor = new ManualMapEditor();

  @Test
  void changesOnlyObjectsIntersectingSelection() {
    ManualCraftovMap map = ManualMapFixtures.validMap().withEvents(List.of());
    CuboidBounds center = new CuboidBounds(170, 30, 170, 330, 100, 330);

    ManualCraftovMap disabled = editor.setEnabledIn(map, center, null, false);

    assertTrue(disabled.spawns().stream().allMatch(SpawnDefinition::enabled));
    assertFalse(disabled.lootPoints().getFirst().enabled());
    assertFalse(disabled.lootZones().getFirst().enabled());
    assertFalse(disabled.aiZones().getFirst().enabled());
    assertFalse(disabled.pois().getFirst().enabled());
    assertTrue(disabled.extractions().stream().allMatch(ExtractionDefinition::enabled));
  }

  @Test
  void updatesLootPropertiesWithoutTouchingOtherTypes() {
    ManualCraftovMap map = ManualMapFixtures.validMap().withEvents(List.of());

    ManualCraftovMap updated = editor.setTierIn(
        editor.setLootTableIn(map, ManualMapFixtures.BOUNDS, "rare"),
        ManualMapFixtures.BOUNDS, 4);

    assertEquals("rare", updated.lootPoints().getFirst().lootTable());
    assertEquals("rare", updated.lootZones().getFirst().lootTable());
    assertEquals(4, updated.lootPoints().getFirst().tier());
    assertEquals(4, updated.lootZones().getFirst().tier());
    assertEquals(4, updated.pois().getFirst().tier());
    assertEquals(map.aiZones(), updated.aiZones());
  }

  @Test
  void filtersAndRemovesObjectsByTypeInsideSelection() {
    ManualCraftovMap map = ManualMapFixtures.validMap().withEvents(List.of());
    CuboidBounds center = new CuboidBounds(170, 30, 170, 330, 100, 330);

    List<MapObject> loot = editor.objectsIn(map, center, MapObjectType.LOOT);
    ManualCraftovMap removed = editor.removeIn(map, center, MapObjectType.LOOT);

    assertEquals(List.of("loot_a"), loot.stream().map(MapObject::id).toList());
    assertTrue(removed.lootPoints().isEmpty());
    assertEquals(map.lootZones(), removed.lootZones());
  }

  @Test
  void objectIdsCannotCollideWithNonSpatialEventIds() {
    ManualCraftovMap map = ManualMapFixtures.validMap();
    SpawnDefinition collision = ManualMapFixtures.spawn("factory_overseer", 100, 100);

    assertThrows(IllegalArgumentException.class, () -> editor.add(map, collision));
  }

  @Test
  void enabledEventResourcesCannotBeDisabledOrRemovedThroughAnySharedMutation() {
    ManualCraftovMap map = ManualMapFixtures.validMap();
    AiZoneDefinition ai = map.aiZones().getFirst();
    AiZoneDefinition disabledAi = new AiZoneDefinition(ai.id(), ai.bounds(), ai.aiType(),
        ai.minimum(), ai.maximum(), ai.respawnSeconds(), ai.difficulty(), ai.team(), false);

    assertThrows(IllegalArgumentException.class, () -> editor.replace(map, disabledAi));
    assertThrows(IllegalArgumentException.class, () -> editor.remove(map, "ai_zone_a"));
    assertThrows(IllegalArgumentException.class,
        () -> editor.setEnabledIn(map, ManualMapFixtures.BOUNDS, MapObjectType.AI_ZONE, false));
    assertThrows(IllegalArgumentException.class,
        () -> editor.removeIn(map, ManualMapFixtures.BOUNDS, MapObjectType.LOOT));
    assertTrue(map.aiZones().getFirst().enabled(), "immutable source draft must remain unchanged");
    assertTrue(map.lootPoints().getFirst().enabled(), "failed batch must be atomic to the caller");
  }
}
