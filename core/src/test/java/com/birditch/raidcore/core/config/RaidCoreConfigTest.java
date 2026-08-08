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

  private static RaidCoreConfig config(int maxPlayers, int teamSize, int durationSeconds,
      int defaultExtractionSeconds) {
    return new RaidCoreConfig(false,
        new RaidCoreConfig.GameSettings(maxPlayers, teamSize, durationSeconds),
        new RaidCoreConfig.ExtractionSettings(defaultExtractionSeconds));
  }

  @Test
  void validConfigurationValidatesWithoutThrowing() {
    assertDoesNotThrow(() -> config(12, 3, 1800, 10).validate());
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
        Arguments.of(config(0, 3, 1800, 10), "game.max-players must be greater than 0"),
        Arguments.of(config(-1, 3, 1800, 10), "game.max-players must be greater than 0"),
        Arguments.of(config(12, 0, 1800, 10), "game.team-size must be greater than 0"),
        Arguments.of(config(12, 3, 0, 10), "game.duration-seconds must be greater than 0"),
        Arguments.of(config(12, 3, 1800, 0),
            "extraction.default-duration-seconds must be greater than 0"));
  }

  @Test
  void aggregatesAllProblemsIntoASingleException() {
    ConfigurationException e =
        assertThrows(ConfigurationException.class, () -> config(0, 0, 0, 0).validate());
    assertEquals("""
        Invalid configuration:
        game.max-players must be greater than 0
        game.team-size must be greater than 0
        game.duration-seconds must be greater than 0
        extraction.default-duration-seconds must be greater than 0""", e.getMessage());
  }

  @Test
  void constructionRejectsNullSections() {
    RaidCoreConfig.GameSettings game = new RaidCoreConfig.GameSettings(12, 3, 1800);
    RaidCoreConfig.ExtractionSettings extraction = new RaidCoreConfig.ExtractionSettings(10);
    assertThrows(NullPointerException.class, () -> new RaidCoreConfig(false, null, extraction));
    assertThrows(NullPointerException.class, () -> new RaidCoreConfig(false, game, null));
  }
}
