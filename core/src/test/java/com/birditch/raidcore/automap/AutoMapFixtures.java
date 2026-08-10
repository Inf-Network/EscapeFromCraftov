package com.birditch.raidcore.automap;

import java.util.ArrayList;
import java.util.List;

final class AutoMapFixtures {

  static final MapBounds BOUNDS = MapBounds.around(0, 0, 256);
  static final int STEP = 16;

  private AutoMapFixtures() {}

  static MapSnapshot snapshot() {
    List<RawTerrainSample> samples = new ArrayList<>();
    for (int x = BOUNDS.minX(); x <= BOUNDS.maxX(); x += STEP) {
      for (int z = BOUNDS.minZ(); z <= BOUNDS.maxZ(); z += STEP) {
        boolean building = Math.abs(x) <= 64 && Math.abs(z) <= 64;
        boolean container = building && Math.floorMod(x + z, 48) == 0;
        samples.add(new RawTerrainSample(x, 65, z, true, false, false, false,
            container, building ? 0.82 : 0.04, building ? 0.72 : 0.0, 0.02));
      }
    }
    return new MapSnapshot("test_world", 8675309L, 63, STEP, BOUNDS, samples);
  }

  static List<TerrainSample> terrain() {
    return new TerrainAnalyzer().analyze(snapshot());
  }

  static AutoSetupSettings settings() {
    return new AutoSetupSettings(256, STEP, 4, 50_000,
        new AutoSetupSettings.SpawnSettings(4, 6, 80, 8),
        new AutoSetupSettings.PoiSettings(256, 4, 8),
        new AutoSetupSettings.LootSettings(1.0, 8, 120),
        new AutoSetupSettings.AiSettings(true, 2, 8),
        new AutoSetupSettings.ExtractionSettings(2, 4, 1, 2, 80, 64, 10),
        new AutoSetupSettings.ValidationSettings(40, 4, 2, 1));
  }

  static List<PointOfInterest> pois() {
    return new POIGenerator().generate(terrain(), BOUNDS, STEP, settings().poi());
  }
}
