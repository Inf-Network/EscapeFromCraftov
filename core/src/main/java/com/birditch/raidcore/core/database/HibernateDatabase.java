package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.core.database.entity.PlayerCosmeticEntity;
import com.birditch.raidcore.core.database.entity.PlayerProfileEntity;
import com.birditch.raidcore.core.database.entity.PlayerSettingEntity;
import com.birditch.raidcore.core.database.entity.PlayerItemEntity;
import com.birditch.raidcore.core.database.entity.RaidInventorySettlementEntity;
import com.birditch.raidcore.core.database.entity.EconomyTransactionEntity;
import com.birditch.raidcore.core.database.entity.ProgressionTransactionEntity;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Function;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;

/// Hibernate ORM runtime backed by a dedicated HikariCP PostgreSQL pool.
public final class HibernateDatabase implements Database {

  private final HikariDataSource dataSource;
  private final StandardServiceRegistry serviceRegistry;
  private final SessionFactory sessionFactory;

  public HibernateDatabase(DatabaseConfig config) {
    Preconditions.checkNotNull(config, "config");
    config.validate();
    this.dataSource = new HikariDataSource(hikariConfig(config));
    StandardServiceRegistry registry = null;
    try {
      registry = new StandardServiceRegistryBuilder()
          .applySetting("hibernate.connection.datasource", dataSource)
          .applySetting("hibernate.connection.provider_disables_autocommit", true)
          .applySetting("hibernate.hbm2ddl.auto", config.orm().schemaAction())
          .applySetting("hibernate.jdbc.batch_size", config.orm().statementBatchSize())
          .applySetting("hibernate.default_batch_fetch_size", config.orm().defaultBatchFetchSize())
          .applySetting("hibernate.order_inserts", true)
          .applySetting("hibernate.order_updates", true)
          .applySetting("hibernate.jdbc.batch_versioned_data", true)
          .applySetting("hibernate.jdbc.time_zone", "UTC")
          .applySetting("hibernate.query.in_clause_parameter_padding", true)
          .applySetting("hibernate.query.fail_on_pagination_over_collection_fetch", true)
          .applySetting("hibernate.show_sql", config.orm().showSql())
          .applySetting("hibernate.format_sql", config.orm().formatSql())
          .applySetting("hibernate.generate_statistics", config.orm().generateStatistics())
          .build();
      TablePrefixNamingStrategy naming = new TablePrefixNamingStrategy(config.tablePrefix());
      Metadata metadata = new MetadataSources(registry)
          .addAnnotatedClass(PlayerProfileEntity.class)
          .addAnnotatedClass(PlayerSettingEntity.class)
          .addAnnotatedClass(PlayerCosmeticEntity.class)
          .addAnnotatedClass(PlayerItemEntity.class)
          .addAnnotatedClass(RaidInventorySettlementEntity.class)
          .addAnnotatedClass(EconomyTransactionEntity.class)
          .addAnnotatedClass(ProgressionTransactionEntity.class)
          .getMetadataBuilder()
          .applyPhysicalNamingStrategy(naming)
          .build();
      naming.prefixIndexNames(metadata);
      this.sessionFactory = metadata.buildSessionFactory();
      this.serviceRegistry = registry;
    } catch (RuntimeException e) {
      if (registry != null) {
        StandardServiceRegistryBuilder.destroy(registry);
      }
      dataSource.close();
      throw e;
    }
  }

  @Override
  public <T> T transaction(Function<Session, T> work) {
    Preconditions.checkNotNull(work, "work");
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        T result = work.apply(session);
        transaction.commit();
        return result;
      } catch (RuntimeException | Error e) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw e;
      }
    }
  }

  @Override
  public void close() {
    try {
      sessionFactory.close();
    } finally {
      try {
        StandardServiceRegistryBuilder.destroy(serviceRegistry);
      } finally {
        dataSource.close();
      }
    }
  }

  private static HikariConfig hikariConfig(DatabaseConfig config) {
    DatabaseConfig.PoolSettings pool = config.pool();
    HikariConfig hikari = new HikariConfig();
    hikari.setDriverClassName("org.postgresql.Driver");
    hikari.setJdbcUrl(
        "jdbc:postgresql://" + config.host() + ":" + config.port() + "/" + config.database());
    hikari.setUsername(config.username());
    hikari.setPassword(config.password());
    hikari.setPoolName(config.applicationName() + "-pool");
    hikari.setAutoCommit(false);
    hikari.setMaximumPoolSize(pool.maximumPoolSize());
    hikari.setMinimumIdle(pool.minimumIdle());
    hikari.setConnectionTimeout(pool.connectionTimeoutMs());
    hikari.setValidationTimeout(pool.validationTimeoutMs());
    hikari.setIdleTimeout(pool.idleTimeoutMs());
    hikari.setMaxLifetime(pool.maxLifetimeMs());
    hikari.setKeepaliveTime(pool.keepaliveTimeMs());
    hikari.setLeakDetectionThreshold(pool.leakDetectionThresholdMs());
    hikari.setInitializationFailTimeout(pool.initializationFailTimeoutMs());
    hikari.setTransactionIsolation(pool.transactionIsolation());
    hikari.setSchema(config.schema());
    if (!pool.connectionInitSql().isBlank()) {
      hikari.setConnectionInitSql(pool.connectionInitSql());
    }
    hikari.setIsolateInternalQueries(pool.isolateInternalQueries());
    hikari.setRegisterMbeans(pool.registerMbeans());
    hikari.addDataSourceProperty("sslmode", config.sslMode());
    hikari.addDataSourceProperty("ApplicationName", config.applicationName());
    hikari.addDataSourceProperty("tcpKeepAlive", config.tcpKeepAlive());
    hikari.addDataSourceProperty("reWriteBatchedInserts", config.rewriteBatchedInserts());
    hikari.addDataSourceProperty("connectTimeout", config.connectTimeoutSeconds());
    hikari.addDataSourceProperty("socketTimeout", config.socketTimeoutSeconds());
    return hikari;
  }
}
