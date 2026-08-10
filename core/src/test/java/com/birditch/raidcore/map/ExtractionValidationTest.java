package com.birditch.raidcore.map;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExtractionValidationTest {

  @Test
  void rejectsMissingItemsAndMalformedNumericConditions() {
    ManualRaidMap source = ManualMapFixtures.validMap();
    ManualRaidMap invalid = new ManualRaidMap(source.schemaVersion(), source.mapId(),
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
}
