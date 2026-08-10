package com.birditch.raidcore.automap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MapValidatorTest {

  @Test
  void reportsErrorsForUnplayableMap() {
    MapValidationReport report = new MapValidator().validate(10, List.of(), List.of(), List.of(),
        List.of(), List.of(), AutoMapFixtures.settings().validation());

    assertTrue(report.hasErrors());
    assertTrue(report.issues().stream().anyMatch(issue ->
        issue.severity() == ValidationSeverity.ERROR));
  }

  @Test
  void acceptsPipelineOutputMeetingThresholds() {
    RaidMapDefinition map = new AutoMapSetupPipeline().generate("test", AutoMapFixtures.snapshot(),
        AutoMapFixtures.settings(), 42L, 1, 123L, _ -> {});

    assertFalse(map.validation().hasErrors(), map.validation().issues().toString());
  }
}
