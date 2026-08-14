package com.birditch.escapefromcraftov.automap;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AutoMapSetupPipelineTest {

  @Test
  void sameSnapshotSettingsAndSeedProduceSameDefinition() {
    AutoMapSetupPipeline pipeline = new AutoMapSetupPipeline();
    CraftovMapDefinition first = pipeline.generate("deterministic", AutoMapFixtures.snapshot(),
        AutoMapFixtures.settings(), 12345L, 1, 1000L, _ -> {});
    CraftovMapDefinition second = pipeline.generate("deterministic", AutoMapFixtures.snapshot(),
        AutoMapFixtures.settings(), 12345L, 1, 1000L, _ -> {});

    assertEquals(first, second);
  }
}
