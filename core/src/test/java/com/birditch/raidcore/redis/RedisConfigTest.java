package com.birditch.raidcore.redis;

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

class RedisConfigTest {

  private static final RedisConfig.PoolSettings VALID_POOL = new RedisConfig.PoolSettings(8, 8, 1);

  private static RedisConfig config(String host, int port, int database) {
    return new RedisConfig(host, port, "secret", database, VALID_POOL);
  }

  private static RedisConfig withPool(int maxTotal, int maxIdle, int minIdle) {
    return new RedisConfig("localhost", 6379, "secret", 0,
        new RedisConfig.PoolSettings(maxTotal, maxIdle, minIdle));
  }

  @Test
  void validConfigurationPasses() {
    assertDoesNotThrow(() -> config("localhost", 6379, 0).validate());
  }

  @Test
  void nullPasswordMeansNoAuthenticationAndIsValid() {
    RedisConfig config = new RedisConfig("localhost", 6379, null, 0, VALID_POOL);
    assertDoesNotThrow(config::validate);
  }

  @Test
  void blankPasswordIsValid() {
    RedisConfig config = new RedisConfig("localhost", 6379, "", 0, VALID_POOL);
    assertDoesNotThrow(config::validate);
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 65535})
  void portBoundariesAreValid(int port) {
    assertDoesNotThrow(() -> config("localhost", port, 0).validate());
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 15})
  void databaseIndexZeroAndAboveIsValid(int database) {
    assertDoesNotThrow(() -> config("localhost", 6379, database).validate());
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("singleProblemConfigs")
  @DisplayName("each invalid setting is reported under its configuration key")
  void reportsEachInvalidSettingByKey(RedisConfig config, String expectedProblem) {
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("Invalid redis configuration:\n" + expectedProblem, e.getMessage());
  }

  static Stream<Arguments> singleProblemConfigs() {
    return Stream.of(
        Arguments.of(config(" ", 6379, 0), "redis.host must not be blank"),
        Arguments.of(config("localhost", 0, 0), "redis.port must be between 1 and 65535"),
        Arguments.of(config("localhost", 65536, 0), "redis.port must be between 1 and 65535"),
        Arguments.of(config("localhost", 6379, -1), "redis.database must not be negative"),
        Arguments.of(withPool(0, 8, 1), "redis.pool.max-total must be greater than 0"),
        Arguments.of(withPool(8, 0, 1), "redis.pool.max-idle must be greater than 0"),
        Arguments.of(withPool(8, 8, 0), "redis.pool.min-idle must be greater than 0"));
  }

  @Test
  void aggregatesAllProblemsIntoASingleException() {
    RedisConfig config =
        new RedisConfig(" ", 0, null, -1, new RedisConfig.PoolSettings(0, 0, 0));
    ConfigurationException e = assertThrows(ConfigurationException.class, config::validate);
    assertEquals("""
        Invalid redis configuration:
        redis.host must not be blank
        redis.port must be between 1 and 65535
        redis.database must not be negative
        redis.pool.max-total must be greater than 0
        redis.pool.max-idle must be greater than 0
        redis.pool.min-idle must be greater than 0""", e.getMessage());
  }

  @Test
  void constructionRejectsNullHostAndPool() {
    assertThrows(NullPointerException.class,
        () -> new RedisConfig(null, 6379, "secret", 0, VALID_POOL));
    assertThrows(NullPointerException.class,
        () -> new RedisConfig("localhost", 6379, "secret", 0, null));
  }
}
