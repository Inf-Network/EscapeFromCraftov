package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.MapValidationIssue;
import java.util.List;

/** Adds runtime-world checks that cannot be performed by the pure map validator. */
@FunctionalInterface
public interface ManualMapEnvironmentValidator {

  List<MapValidationIssue> validate(ManualCraftovMap map);

  static ManualMapEnvironmentValidator none() {
    return _ -> List.of();
  }
}
