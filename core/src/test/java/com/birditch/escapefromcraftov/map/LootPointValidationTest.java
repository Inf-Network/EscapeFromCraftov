package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LootPointValidationTest {

  @Test
  void rejectsUnknownLootTablesAndPointsOutsideBounds() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    ManualCraftovMap invalid = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), true,
        source.metadata(), source.spawns(), List.of(new LootPointDefinition("bad_loot",
            new BlockPosition(900, 64, 900), "virtual", "unknown", 1, true, true)),
        List.of(), source.aiZones(), source.extractions(), source.pois());

    ManualMapValidationResult result = new ManualMapValidator().validate(invalid,
        new ManualMapReferences(true, Set.of("default"), Set.of()),
        ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("outside_bounds")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("loot_table_missing")));
  }
}
