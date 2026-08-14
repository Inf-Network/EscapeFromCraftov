package com.birditch.escapefromcraftov.automap;

import java.util.ArrayList;
import java.util.List;

/// Produces AI regions only; combat/pathfinding implementations remain separate.
public final class AISpawnGenerator {

  public List<AiSpawnRegion> generate(List<PointOfInterest> pois,
      AutoSetupSettings.AiSettings settings) {
    if (!settings.enabled()) return List.of();
    List<AiSpawnRegion> result = new ArrayList<>();
    for (int index = 0; index < pois.size(); index++) {
      PointOfInterest poi = pois.get(index);
      int bonus = switch (poi.type()) {
        case LOW_VALUE -> 0;
        case MEDIUM_VALUE -> 2;
        case HIGH_VALUE, SPECIAL -> 4;
      };
      int minimum = Math.min(settings.maximumPerRegion(), settings.minimumPerRegion() + bonus);
      int maximum = Math.min(settings.maximumPerRegion(), minimum + 3);
      result.add(new AiSpawnRegion(String.format("ai-%02d", index + 1), poi.center(),
          Math.max(12, poi.radius()), minimum, maximum,
          Math.min(1, 0.2 + poi.type().ordinal() * 0.25)));
    }
    return List.copyOf(result);
  }
}
