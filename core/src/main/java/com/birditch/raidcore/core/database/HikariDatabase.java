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
    HikariConfig hikari = new HikariConfig();
    // Explicit driver class instead of DriverManager SPI: plugin classloaders do not
    // see service-loader drivers, and the Shadow relocation rewrites this literal to
    // the relocated driver class inside the plugin jar.
    hikari.setDriverClassName("org.postgresql.Driver");
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
