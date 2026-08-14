package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ManualMapEditorBatchTest {

  private final ManualMapEditor editor = new ManualMapEditor();

  @Test
  void changesOnlyObjectsIntersectingSelection() {
    ManualCraftovMap map = ManualMapFixtures.validMap();
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
    ManualCraftovMap map = ManualMapFixtures.validMap();

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
    ManualCraftovMap map = ManualMapFixtures.validMap();
    CuboidBounds center = new CuboidBounds(170, 30, 170, 330, 100, 330);

    List<MapObject> loot = editor.objectsIn(map, center, MapObjectType.LOOT);
    ManualCraftovMap removed = editor.removeIn(map, center, MapObjectType.LOOT);

    assertEquals(List.of("loot_a"), loot.stream().map(MapObject::id).toList());
    assertTrue(removed.lootPoints().isEmpty());
    assertEquals(map.lootZones(), removed.lootZones());
  }
}
