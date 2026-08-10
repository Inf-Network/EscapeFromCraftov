package com.birditch.raidcore.core.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseConfigTest {

  private static final DatabaseConfig.PoolSettings VALID_POOL =
      new DatabaseConfig.PoolSettings(10, 2, 10_000L);

  private static DatabaseConfig postgres(String host, int port, String database, String username) {
    return new DatabaseConfig(host, port, database, username, "secret", VALID_POOL);
  }

  private static DatabaseConfig withPool(int maximumPoolSize, int minimumIdle) {
    return new DatabaseConfig("localhost", 5432, "raidcore", "raidcore",
        "secret", new DatabaseConfig.PoolSettings(maximumPoolSize, minimumIdle, 10_000L));
  }

  private static DatabaseConfig withTablePrefix(String tablePrefix) {
    return new DatabaseConfig("localhost", 5432, "raidcore", "raidcore", "secret", "public",
        tablePrefix, "prefer", "RaidCore", true, true, 10, 30, VALID_POOL,
        DatabaseConfig.OrmSettings.defaults());
  }

  @Test
  void validConfigurationPasses() {
    assertDoesNotThrow(() -> postgres("localhost", 5432, "raidcore", "raidcore").validate());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 65535})
  void portBoundariesAreValid(int port) {
    assertDoesNotThrow(() -> postgres("localhost", port, "raidcore", "raidcore").validate());
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "ec_", "s1", "a_b_c", "exactly_twenty_chars"})
  void validTablePrefixesPass(String tablePrefix) {
    assertDoesNotThrow(() -> withTablePrefix(tablePrefix).validate());
  }

  @Test
  void blankPasswordIsAllowed() {
    DatabaseConfig config = new DatabaseConfig("localhost", 5432,
        "raidcore", "raidcore", "", VALID_POOL);
    assertDoesNotThrow(config::validate);
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("singleProblemConfigs")
  @DisplayName("each invalid setting is reported under its configuration key")
  void reportsEachInvalidSettingByKey(DatabaseConfig config, String expectedProblem) {
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("Invalid database configuration:\n" + expectedProblem, e.getMessage());
  }

  static Stream<Arguments> singleProblemConfigs() {
    return Stream.of(
        Arguments.of(postgres(" ", 5432, "raidcore", "raidcore"),
            "database.host must not be blank"),
        Arguments.of(postgres("localhost", 0, "raidcore", "raidcore"),
            "database.port must be between 1 and 65535"),
        Arguments.of(postgres("localhost", 65536, "raidcore", "raidcore"),
            "database.port must be between 1 and 65535"),
        Arguments.of(postgres("localhost", 5432, " ", "raidcore"),
            "database.database must not be blank"),
        Arguments.of(postgres("localhost", 5432, "raidcore", " "),
            "database.username must not be blank"),
        Arguments.of(withPool(0, 0), "database.pool.maximum-pool-size must be greater than 0"),
        Arguments.of(withPool(10, -1), "database.pool.minimum-idle must not be negative"),
        Arguments.of(withTablePrefix("1abc"),
            "database.table-prefix must be empty or match [a-z][a-z0-9_]* with at most 20 characters"),
        Arguments.of(withTablePrefix("EC_"),
            "database.table-prefix must be empty or match [a-z][a-z0-9_]* with at most 20 characters"),
        Arguments.of(withTablePrefix("raid core"),
            "database.table-prefix must be empty or match [a-z][a-z0-9_]* with at most 20 characters"),
        Arguments.of(withTablePrefix("way_too_long_prefix_x"),
            "database.table-prefix must be empty or match [a-z][a-z0-9_]* with at most 20 characters"));
  }

  @Test
  void aggregatesAllProblemsIntoASingleException() {
    DatabaseConfig config = new DatabaseConfig(" ", 0, " ", " ", "",
        new DatabaseConfig.PoolSettings(0, -1, 0L));
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("""
        Invalid database configuration:
        database.host must not be blank
        database.port must be between 1 and 65535
        database.database must not be blank
        database.username must not be blank
        database.pool.maximum-pool-size must be greater than 0
        database.pool.minimum-idle must not be negative
        database.pool.connection-timeout must be at least 250 ms
        database.pool.validation-timeout must be at least 250 ms and less than connection-timeout""",
        e.getMessage());
  }

  @Test
  void constructionRejectsNullComponents() {
    assertThrows(NullPointerException.class, () -> new DatabaseConfig(null, 5432,
        "raidcore", "raidcore", "secret", VALID_POOL));
    assertThrows(NullPointerException.class, () -> new DatabaseConfig("localhost", 5432,
        null, "raidcore", "secret", VALID_POOL));
    assertThrows(NullPointerException.class, () -> new DatabaseConfig("localhost", 5432,
        "raidcore", "raidcore", "secret", null));
  }
}
