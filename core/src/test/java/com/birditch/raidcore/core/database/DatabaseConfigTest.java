package com.birditch.raidcore.core.database;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class DatabaseConfigTest {

  private static final DatabaseConfig.PoolSettings VALID_POOL =
      new DatabaseConfig.PoolSettings(10, 2, 10_000L);

  private static DatabaseConfig postgres(String host, int port, String database, String username) {
    return new DatabaseConfig(DatabaseType.POSTGRESQL, host, port, database, username, "secret",
        VALID_POOL);
  }

  private static DatabaseConfig withPool(int maximumPoolSize, int minimumIdle) {
    return new DatabaseConfig(DatabaseType.POSTGRESQL, "localhost", 5432, "raidcore", "raidcore",
        "secret", new DatabaseConfig.PoolSettings(maximumPoolSize, minimumIdle, 10_000L));
  }

  @Test
  void memoryTypeSkipsConnectionValidationEntirely() {
    DatabaseConfig config = new DatabaseConfig(DatabaseType.MEMORY, " ", 0, " ", " ", "",
        new DatabaseConfig.PoolSettings(0, 0, 0L));
    assertDoesNotThrow(config::validate);
  }

  @Test
  void validPostgresConfigurationPasses() {
    assertDoesNotThrow(() -> postgres("localhost", 5432, "raidcore", "raidcore").validate());
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 65535})
  void portBoundariesAreValid(int port) {
    assertDoesNotThrow(() -> postgres("localhost", port, "raidcore", "raidcore").validate());
  }

  @Test
  void blankPasswordIsAllowed() {
    DatabaseConfig config = new DatabaseConfig(DatabaseType.POSTGRESQL, "localhost", 5432,
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
        Arguments.of(withPool(0, 2), "database.pool.maximum-pool-size must be greater than 0"),
        Arguments.of(withPool(10, 0), "database.pool.minimum-idle must be greater than 0"));
  }

  @Test
  void aggregatesAllProblemsIntoASingleException() {
    DatabaseConfig config = new DatabaseConfig(DatabaseType.POSTGRESQL, " ", 0, " ", " ", "",
        new DatabaseConfig.PoolSettings(0, 0, 0L));
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("""
        Invalid database configuration:
        database.host must not be blank
        database.port must be between 1 and 65535
        database.database must not be blank
        database.username must not be blank
        database.pool.maximum-pool-size must be greater than 0
        database.pool.minimum-idle must be greater than 0""", e.getMessage());
  }

  @Test
  void constructionRejectsNullComponents() {
    assertThrows(NullPointerException.class, () -> new DatabaseConfig(null, "localhost", 5432,
        "raidcore", "raidcore", "secret", VALID_POOL));
    assertThrows(NullPointerException.class, () -> new DatabaseConfig(DatabaseType.MEMORY, null,
        5432, "raidcore", "raidcore", "secret", VALID_POOL));
    assertThrows(NullPointerException.class, () -> new DatabaseConfig(DatabaseType.MEMORY,
        "localhost", 5432, "raidcore", "raidcore", "secret", null));
  }

  @Nested
  @DisplayName("DatabaseType.parse")
  class Parse {

    @ParameterizedTest
    @CsvSource({
        "memory, MEMORY",
        "MEMORY, MEMORY",
        "Memory, MEMORY",
        "postgresql, POSTGRESQL",
        "PostgreSQL, POSTGRESQL",
        "POSTGRESQL, POSTGRESQL"
    })
    void parsesKnownTypesCaseInsensitively(String raw, DatabaseType expected) {
      assertEquals(expected, DatabaseType.parse(raw));
    }

    @Test
    void unknownTypeThrowsConfigurationExceptionNamingTheValue() {
      ConfigurationException e =
          assertThrows(ConfigurationException.class, () -> DatabaseType.parse("oracle"));
      assertTrue(e.getMessage().contains("oracle"), "message should echo the raw value");
    }

    @Test
    void nullValueIsRejected() {
      assertThrows(NullPointerException.class, () -> DatabaseType.parse(null));
    }
  }
}
