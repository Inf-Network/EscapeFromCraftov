package com.birditch.escapefromcraftov.api.game;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.Test;

final class CraftovExtractionRouteScopeTest {

  @Test
  void rejectsInvalidSpawnGroupIds() {
    assertThrows(IllegalArgumentException.class,
        () -> new CraftovExtractionRouteScope(false, Set.of("North-Side")));
  }
}
