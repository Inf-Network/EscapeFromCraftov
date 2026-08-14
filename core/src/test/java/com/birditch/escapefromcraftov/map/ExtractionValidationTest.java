package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionValidationTest {

  @Test
  void rejectsMissingItemsAndMalformedNumericConditions() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    ManualCraftovMap invalid = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), true,
        source.metadata(), source.spawns(), source.lootPoints(), source.lootZones(),
        source.aiZones(), List.of(
            ManualMapFixtures.extraction("normal", 10, 450, ManualExtractionType.NORMAL, null),
            ManualMapFixtures.extraction("key_exit", 250, 450,
                ManualExtractionType.ITEM_REQUIRED, "unknown_key"),
            ManualMapFixtures.extraction("paid_exit", 450, 450,
                ManualExtractionType.PAID, "many")), source.pois());

    ManualMapValidationResult result = new ManualMapValidator().validate(invalid,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("required_item_missing") && "key_exit".equals(issue.objectId())));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("condition_invalid") && "paid_exit".equals(issue.objectId())));
  }

  @Test
  void rejectsUnknownDuplicateAndUncoveredAuthoredRouteGroups() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    SpawnDefinition north = new SpawnDefinition("spawn_a", source.spawns().getFirst().position(),
        0, 0, "players", 1, true, null, "north");
    SpawnDefinition south = new SpawnDefinition("spawn_b", source.spawns().getLast().position(),
        0, 0, "players", 1, true, null, "south");
    ExtractionDefinition northExit = new ExtractionDefinition("north_gate", "North",
        new CuboidBounds(10, 60, 450, 18, 70, 458), ManualExtractionType.NORMAL, 10,
        null, true, List.of(), new ExtractionRouteScope(false,
            List.of("north", "north", "ghost")));
    ManualCraftovMap invalid = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), true,
        source.metadata(), List.of(north, south), source.lootPoints(), source.lootZones(),
        source.aiZones(), List.of(northExit), source.pois());

    ManualMapValidationResult result = new ManualMapValidator().validate(invalid,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("route_group_duplicate")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("route_group_unknown")));
    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("route_group_without_reliable_exit")
            && issue.message().contains("south")));
  }

  @Test
  void anyExplicitScopeRequiresEnabledScopedCoverage() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    List<SpawnDefinition> spawns = source.spawns().stream().map(spawn ->
        new SpawnDefinition(spawn.id(), spawn.position(), spawn.yaw(), spawn.pitch(),
            spawn.team(), spawn.weight(), spawn.enabled(), spawn.spawnRegion(), "players"))
        .toList();
    ExtractionDefinition legacyExit = new ExtractionDefinition("legacy_gate", "Legacy",
        new CuboidBounds(10, 60, 450, 18, 70, 458), ManualExtractionType.NORMAL, 10,
        null, true, List.of(), null);
    ExtractionDefinition disabledGlobal = new ExtractionDefinition("disabled_global", "Disabled",
        new CuboidBounds(30, 60, 450, 38, 70, 458), ManualExtractionType.NORMAL, 10,
        null, false, List.of(), new ExtractionRouteScope(true, List.of()));
    ManualCraftovMap invalid = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(), true,
        source.metadata(), spawns, source.lootPoints(), source.lootZones(), source.aiZones(),
        List.of(legacyExit, disabledGlobal), source.pois());

    ManualMapValidationResult result = new ManualMapValidator().validate(invalid,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(result.issues().stream().anyMatch(issue ->
        issue.code().equals("route_group_without_reliable_exit")
            && issue.message().contains("players")));
  }

  @Test
  void eventCompletedRuleRejectsBlankMissingAndDisabledEvents() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    ExtractionDefinition timed = source.extractions().get(1);
    ManualMapEditor editor = new ManualMapEditor();

    ManualCraftovMap blank = editor.replace(source, withEventRule(timed, ""));
    ManualCraftovMap missing = editor.replace(source, withEventRule(timed, "ghost_event"));
    MapEventDefinition event = source.events().getFirst();
    ManualCraftovMap disabled = editor.replace(source.withEvents(List.of(
        new MapEventDefinition(event.id(), event.displayName(), event.type(), event.aiZoneId(),
            event.cacheLootPointId(), event.startDelaySeconds(), event.healthMultiplier(),
            event.damageMultiplier(), event.protectionSeconds(), false))),
        withEventRule(timed, event.id()));

    ManualMapValidator validator = new ManualMapValidator();
    ManualMapValidationResult blankResult = validator.validate(blank,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());
    ManualMapValidationResult missingResult = validator.validate(missing,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());
    ManualMapValidationResult disabledResult = validator.validate(disabled,
        ManualMapFixtures.references(), ManualMapValidationSettings.defaults());

    assertTrue(blankResult.issues().stream().anyMatch(issue ->
        issue.code().equals("extraction_event_invalid")
            && timed.id().equals(issue.objectId())));
    assertTrue(missingResult.issues().stream().anyMatch(issue ->
        issue.code().equals("extraction_event_missing")
            && timed.id().equals(issue.objectId())));
    assertTrue(disabledResult.issues().stream().anyMatch(issue ->
        issue.code().equals("extraction_event_missing")
            && timed.id().equals(issue.objectId())));
  }

  private static ExtractionDefinition withEventRule(
      ExtractionDefinition source, String eventId) {
    return new ExtractionDefinition(source.id(), source.displayName(), source.bounds(),
        source.extractionType(), source.durationSeconds(), source.conditionValue(),
        source.enabled(), List.of(new ExtractionRuleDefinition.EventCompleted(eventId)),
        source.routeScope());
  }
}
