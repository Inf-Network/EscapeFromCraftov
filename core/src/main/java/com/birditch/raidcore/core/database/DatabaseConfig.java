package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.config.ConfigurationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/// PostgreSQL, HikariCP and Hibernate ORM settings. Validation aggregates all
/// related and cross-field failures so an operator can fix one boot at a time.
public record DatabaseConfig(
    String host,
    int port,
    String database,
    String username,
    String password,
    String schema,
    String sslMode,
    String applicationName,
    boolean tcpKeepAlive,
    boolean rewriteBatchedInserts,
    int connectTimeoutSeconds,
    int socketTimeoutSeconds,
    PoolSettings pool,
    OrmSettings orm) {

  private static final Set<String> SSL_MODES =
      Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");
  private static final Set<String> ISOLATION_LEVELS = Set.of(
      "TRANSACTION_READ_UNCOMMITTED",
      "TRANSACTION_READ_COMMITTED",
      "TRANSACTION_REPEATABLE_READ",
      "TRANSACTION_SERIALIZABLE");
  private static final Set<String> SCHEMA_ACTIONS =
      Set.of("none", "validate", "update", "create", "create-drop");

  public DatabaseConfig {
    Preconditions.checkNotNull(host, "host");
    Preconditions.checkNotNull(database, "database");
    Preconditions.checkNotNull(username, "username");
    Preconditions.checkNotNull(password, "password");
    Preconditions.checkNotNull(schema, "schema");
    Preconditions.checkNotNull(sslMode, "sslMode");
    Preconditions.checkNotNull(applicationName, "applicationName");
    Preconditions.checkNotNull(pool, "pool");
    Preconditions.checkNotNull(orm, "orm");
  }

  /// Compatibility constructor used by pure unit tests and embedders.
  public DatabaseConfig(String host, int port, String database, String username,
      String password, PoolSettings pool) {
    this(host, port, database, username, password, "public", "prefer", "RaidCore", true, true,
        10, 30, pool, OrmSettings.defaults());
  }

  public void validate() {
    List<String> problems = new ArrayList<>();
    if (host.isBlank()) {
      problems.add("database.host must not be blank");
    }
    if (port < 1 || port > 65535) {
      problems.add("database.port must be between 1 and 65535");
    }
    if (database.isBlank()) {
      problems.add("database.database must not be blank");
    }
    if (username.isBlank()) {
      problems.add("database.username must not be blank");
    }
    if (schema.isBlank()) {
      problems.add("database.schema must not be blank");
    }
    if (!SSL_MODES.contains(sslMode)) {
      problems.add("database.ssl-mode must be one of " + SSL_MODES);
    }
    if (applicationName.isBlank()) {
      problems.add("database.application-name must not be blank");
    }
    if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 300) {
      problems.add("database.connect-timeout-seconds must be between 1 and 300");
    }
    if (socketTimeoutSeconds < 0) {
      problems.add("database.socket-timeout-seconds must not be negative");
    }
    validatePool(problems);
    validateOrm(problems);
    if (!problems.isEmpty()) {
      throw new ConfigurationException(
          "Invalid database configuration:\n" + String.join("\n", problems));
    }
  }

  private void validatePool(List<String> problems) {
    if (pool.maximumPoolSize() <= 0) {
      problems.add("database.pool.maximum-pool-size must be greater than 0");
    }
    if (pool.minimumIdle() < 0) {
      problems.add("database.pool.minimum-idle must not be negative");
    } else if (pool.minimumIdle() > pool.maximumPoolSize()) {
      problems.add("database.pool.minimum-idle must not exceed maximum-pool-size");
    }
    if (pool.connectionTimeoutMs() < 250) {
      problems.add("database.pool.connection-timeout must be at least 250 ms");
    }
    if (pool.validationTimeoutMs() < 250
        || pool.validationTimeoutMs() >= pool.connectionTimeoutMs()) {
      problems.add("database.pool.validation-timeout must be at least 250 ms and less than connection-timeout");
    }
    if (pool.idleTimeoutMs() != 0 && pool.idleTimeoutMs() < 10_000) {
      problems.add("database.pool.idle-timeout must be 0 or at least 10000 ms");
    }
    if (pool.maxLifetimeMs() != 0 && pool.maxLifetimeMs() < 30_000) {
      problems.add("database.pool.max-lifetime must be 0 or at least 30000 ms");
    }
    if (pool.keepaliveTimeMs() != 0 && pool.keepaliveTimeMs() < 30_000) {
      problems.add("database.pool.keepalive-time must be 0 or at least 30000 ms");
    }
    if (pool.keepaliveTimeMs() != 0 && pool.maxLifetimeMs() != 0
        && pool.keepaliveTimeMs() >= pool.maxLifetimeMs()) {
      problems.add("database.pool.keepalive-time must be less than max-lifetime");
    }
    if (pool.leakDetectionThresholdMs() != 0 && pool.leakDetectionThresholdMs() < 2_000) {
      problems.add("database.pool.leak-detection-threshold must be 0 or at least 2000 ms");
    }
    if (pool.initializationFailTimeoutMs() < -1) {
      problems.add("database.pool.initialization-fail-timeout must be at least -1");
    }
    if (!ISOLATION_LEVELS.contains(pool.transactionIsolation())) {
      problems.add("database.pool.transaction-isolation must be one of " + ISOLATION_LEVELS);
    }
  }

  private void validateOrm(List<String> problems) {
    if (!SCHEMA_ACTIONS.contains(orm.schemaAction())) {
      problems.add("database.orm.schema-action must be one of " + SCHEMA_ACTIONS);
    }
    if (orm.statementBatchSize() < 0 || orm.statementBatchSize() > 1_000) {
      problems.add("database.orm.statement-batch-size must be between 0 and 1000");
    }
    if (orm.defaultBatchFetchSize() < 0 || orm.defaultBatchFetchSize() > 1_000) {
      problems.add("database.orm.default-batch-fetch-size must be between 0 and 1000");
    }
  }

  public record PoolSettings(
      int maximumPoolSize,
      int minimumIdle,
      long connectionTimeoutMs,
      long validationTimeoutMs,
      long idleTimeoutMs,
      long maxLifetimeMs,
      long keepaliveTimeMs,
      long leakDetectionThresholdMs,
      long initializationFailTimeoutMs,
      String transactionIsolation,
      String connectionInitSql,
      boolean isolateInternalQueries,
      boolean registerMbeans) {

    public PoolSettings {
      Preconditions.checkNotNull(transactionIsolation, "transactionIsolation");
      Preconditions.checkNotNull(connectionInitSql, "connectionInitSql");
    }

    public PoolSettings(int maximumPoolSize, int minimumIdle, long connectionTimeoutMs) {
      this(maximumPoolSize, minimumIdle, connectionTimeoutMs, 5_000, 600_000, 1_800_000,
          120_000, 0, 1, "TRANSACTION_READ_COMMITTED", "", true, false);
    }
  }

  public record OrmSettings(
      String schemaAction,
      int statementBatchSize,
      int defaultBatchFetchSize,
      boolean showSql,
      boolean formatSql,
      boolean generateStatistics) {

    public OrmSettings {
      Preconditions.checkNotNull(schemaAction, "schemaAction");
    }

    public static OrmSettings defaults() {
      return new OrmSettings("update", 25, 16, false, false, false);
    }
  }
}
