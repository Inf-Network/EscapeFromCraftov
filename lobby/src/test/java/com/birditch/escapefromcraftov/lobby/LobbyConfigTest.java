package com.birditch.escapefromcraftov.lobby;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.core.config.ConfigurationException;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LobbyConfigTest {

  @Test
  void rejectsNonPositiveQueueMinimumPlayers() {
    CraftovMapId map = CraftovMapId.of("debug_zone");
    LobbyConfig config = new LobbyConfig(
        1, 0, 30, 30, true, true, 20, 5, map, Set.of(map));

    ConfigurationException error = assertThrows(ConfigurationException.class, config::validate);
    assertTrue(error.getMessage().contains("queue.minimum-players"));
  }
}
