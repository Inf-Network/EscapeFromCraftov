package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class MapValidationTest {

  @Test
  void rejectsSpawnTeamLabelsThatCannotMapToRuntimeTeams() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    SpawnDefinition invalid = new SpawnDefinition("spawn_invalid", new BlockPosition(250, 64, 20),
        0, 0, "builders", 1, true, null);
    ManualCraftovMap map = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), source.enabled(),
        source.metadata(), java.util.List.of(source.spawns().getFirst(), invalid),
        source.lootPoints(), source.lootZones(), source.aiZones(), source.extractions(), source.pois());

    ManualMapValidationResult result = new ManualMapValidator().validate(
        map, ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("spawn_team_invalid")
            && issue.affectedObjectId().orElse("").equals("spawn_invalid")));
  }

  private final ManualMapValidator validator = new ManualMapValidator();

  @Test
  void acceptsCompleteAuthoredMap() {
    ManualMapValidationResult result = validator.validate(ManualMapFixtures.validMap(),
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertFalse(result.hasErrors(), result.issues().toString());
    assertTrue(result.qualityScore() >= 90);
  }

  @Test
  void reportsBoundsWorldCountsAndDuplicateIds() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    var loot = new ArrayList<>(source.lootPoints());
    loot.add(new LootPointDefinition("spawn_a", new BlockPosition(900, 64, 900),
        "virtual", "default", 1, true, true));
    ManualCraftovMap invalid = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), null, source.revision(), source.enabled(),
        source.metadata(), source.spawns().subList(0, 1), loot, source.lootZones(),
        source.aiZones(), source.extractions().subList(0, 1), source.pois());

    ManualMapValidationResult result = validator.validate(invalid,
        new ManualMapReferences(false, ManualMapFixtures.references().lootTables(),
            ManualMapFixtures.references().itemIds()), ManualMapValidationSettings.defaults());

    assertTrue(result.hasErrors());
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("bounds_missing")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("world_missing")));
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals("duplicate_id")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("spawns_insufficient")));
  }

  @Test
  void reportsMissingRiskRewardSectionsAsWarnings() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    ManualCraftovMap incomplete = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), false,
        source.metadata(), source.spawns(), source.lootPoints(), java.util.List.of(),
        java.util.List.of(), source.extractions(), java.util.List.of());

    ManualMapValidationResult result = validator.validate(incomplete,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertFalse(result.hasErrors(), result.issues().toString());
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("loot_zones_insufficient")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("ai_zones_insufficient")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("pois_insufficient")));
  }
}
