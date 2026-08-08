package com.birditch.raidcore.core.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RaidCoreConfigTest {

  private static RaidCoreConfig.GameSettings game(int maxPlayers, int teamSize,
      int durationSeconds, int maxInstances) {
    return new RaidCoreConfig.GameSettings(maxPlayers, teamSize, durationSeconds, maxInstances);
  }

  private static RaidCoreConfig config(RaidCoreConfig.GameSettings game,
      int defaultExtractionSeconds) {
    return new RaidCoreConfig(false, game,
        new RaidCoreConfig.ExtractionSettings(defaultExtractionSeconds));
  }

  @Test
  void validConfigurationValidatesWithoutThrowing() {
    assertDoesNotThrow(() -> config(game(12, 3, 1800, 4), 10).validate());
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("singleProblemConfigs")
  @DisplayName("each invalid setting is reported under its configuration key")
  void reportsEachInvalidSettingByKey(RaidCoreConfig config, String expectedProblem) {
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("Invalid configuration:\n" + expectedProblem, e.getMessage());
  }

  static Stream<Arguments> singleProblemConfigs() {
    return Stream.of(
        Arguments.of(config(game(0, 3, 1800, 4), 10),
            "game.max-players must be greater than 0"),
        Arguments.of(config(game(-1, 3, 1800, 4), 10),
            "game.max-players must be greater than 0"),
        Arguments.of(config(game(12, 0, 1800, 4), 10),
            "game.team-size must be greater than 0"),
        Arguments.of(config(game(12, 3, 0, 4), 10),
            "game.duration-seconds must be greater than 0"),
        Arguments.of(config(game(12, 3, 1800, 0), 10),
            "game.max-instances must be greater than 0"),
        Arguments.of(config(game(12, 3, 1800, -1), 10),
            "game.max-instances must be greater than 0"),
        Arguments.of(config(game(12, 3, 1800, 4), 0),
            "extraction.default-duration-seconds must be greater than 0"));
  }

  @Test
  void aggregatesAllProblemsIntoASingleException() {
    ConfigurationException e = assertThrows(ConfigurationException.class,
        () -> config(game(0, 0, 0, 0), 0).validate());
    assertEquals("""
        Invalid configuration:
        game.max-players must be greater than 0
        game.team-size must be greater than 0
        game.duration-seconds must be greater than 0
        game.max-instances must be greater than 0
        extraction.default-duration-seconds must be greater than 0""", e.getMessage());
  }

  @Test
  void constructionRejectsNullSections() {
    RaidCoreConfig.GameSettings game = game(12, 3, 1800, 4);
    RaidCoreConfig.ExtractionSettings extraction = new RaidCoreConfig.ExtractionSettings(10);
    assertThrows(NullPointerException.class, () -> new RaidCoreConfig(false, null, extraction));
    assertThrows(NullPointerException.class, () -> new RaidCoreConfig(false, game, null));
  }
}
