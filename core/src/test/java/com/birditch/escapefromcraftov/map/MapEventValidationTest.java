package com.birditch.escapefromcraftov.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapEventValidationTest {

  private final ManualMapValidator validator = new ManualMapValidator();

  @Test
  void acceptsEnabledEventWithEnabledUniqueReferencesAndBoundedTuning() {
    ManualMapValidationResult result = validate(ManualMapFixtures.validMap());

    assertFalse(result.hasErrors(), result.issues().toString());
  }

  @Test
  void rejectsDuplicateAndMapObjectEventIds() {
    ManualCraftovMap map = ManualMapFixtures.validMap().withEvents(List.of(
        event("spawn_a", "ai_zone_a", "loot_a", 10, 2, 1, 10, true),
        event("spawn_a", "ai_zone_a", "loot_a", 10, 2, 1, 10, false)));

    ManualMapValidationResult result = validate(map);

    assertCode(result, "event_id_duplicate");
    assertCode(result, "event_id_conflict");
  }

  @Test
  void rejectsMissingDisabledAndReusedReferences() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    AiZoneDefinition disabledAi = new AiZoneDefinition("disabled_ai",
        new CuboidBounds(20, 50, 200, 40, 80, 220), "scav", 1, 1, 60, 1,
        "scav", false);
    LootPointDefinition disabledLoot = new LootPointDefinition("disabled_cache",
        new BlockPosition(50, 64, 250), "virtual", "default", 4, true, false);
    ManualCraftovMap map = new ManualCraftovMap(source.schemaVersion(), source.mapId(),
        source.displayName(), source.worldName(), source.bounds(), source.revision(),
        source.enabled(), source.metadata(), source.spawns(),
        List.of(source.lootPoints().getFirst(), disabledLoot), source.lootZones(),
        List.of(source.aiZones().getFirst(), disabledAi), source.extractions(), source.pois(),
        source.actionTiers(), List.of(
            event("event_a", "ai_zone_a", "loot_a", 10, 2, 1, 10, true),
            event("event_b", "ai_zone_a", "loot_a", 10, 2, 1, 10, true),
            event("event_c", "missing_ai", "missing_cache", 10, 2, 1, 10, true),
            event("event_d", "disabled_ai", "disabled_cache", 10, 2, 1, 10, true)));

    ManualMapValidationResult result = validate(map);

    assertCode(result, "event_ai_zone_reused");
    assertCode(result, "event_cache_loot_reused");
    assertCode(result, "event_ai_zone_missing");
    assertCode(result, "event_cache_loot_missing");
    assertCode(result, "event_ai_zone_disabled");
    assertCode(result, "event_cache_loot_disabled");
  }

  @Test
  void rejectsUnsafeEventTuningButIgnoresDisabledEvents() {
    ManualCraftovMap source = ManualMapFixtures.validMap();
    MapEventDefinition invalid = event("unsafe_event", "ai_zone_a", "loot_a", -1,
        Double.NaN, 9, 301, true);
    ManualMapValidationResult result = validate(source.withEvents(List.of(invalid)));

    assertCode(result, "event_start_delay_invalid");
    assertCode(result, "event_health_multiplier_invalid");
    assertCode(result, "event_damage_multiplier_invalid");
    assertCode(result, "event_protection_seconds_invalid");

    ManualMapValidationResult disabled = validate(source.withEvents(List.of(
        event("disabled_event", "missing_ai", "missing_cache", -1,
            Double.NaN, 9, 301, false))));
    assertFalse(disabled.hasErrors(), disabled.issues().toString());
  }

  private ManualMapValidationResult validate(ManualCraftovMap map) {
    return validator.validate(map, ManualMapFixtures.references(),
        ManualMapValidationSettings.defaults());
  }

  private static MapEventDefinition event(String id, String aiZoneId, String cacheId,
      int delay, double health, double damage, int protection, boolean enabled) {
    return new MapEventDefinition(id, id, MapEventType.HIGH_VALUE_TARGET, aiZoneId, cacheId,
        delay, health, damage, protection, enabled);
  }

  private static void assertCode(ManualMapValidationResult result, String code) {
    assertTrue(result.issues().stream().anyMatch(issue -> issue.code().equals(code)),
        () -> "Missing " + code + " in " + result.issues());
  }
}
