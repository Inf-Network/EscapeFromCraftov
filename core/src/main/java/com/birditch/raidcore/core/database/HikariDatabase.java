package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;

/// [Database] backed by a HikariCP pool over PostgreSQL. The pool starts eagerly in
/// the constructor and must be released via [#close] on shutdown.
public final class HikariDatabase implements Database {

  private final HikariDataSource dataSource;

  public HikariDatabase(DatabaseConfig config) {
    Preconditions.checkNotNull(config, "config");
    Preconditions.checkArgument(
        config.type() == DatabaseType.POSTGRESQL,
        "HikariDatabase requires a POSTGRESQL configuration");
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl(
        "jdbc:postgresql://" + config.host() + ":" + config.port() + "/" + config.database());
    hikari.setUsername(config.username());
    hikari.setPassword(config.password());
    hikari.setPoolName("raidcore");
    hikari.setMaximumPoolSize(config.pool().maximumPoolSize());
    hikari.setMinimumIdle(config.pool().minimumIdle());
    hikari.setConnectionTimeout(config.pool().connectionTimeoutMs());
    this.dataSource = new HikariDataSource(hikari);
  }

  @Override
  public Connection connection() throws SQLException {
    return dataSource.getConnection();
  }

  @Override
  public void close() {
    dataSource.close();
  }
}
