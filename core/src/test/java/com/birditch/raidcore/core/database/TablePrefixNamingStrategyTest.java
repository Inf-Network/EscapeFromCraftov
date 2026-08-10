package com.birditch.raidcore.core.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.birditch.raidcore.core.database.entity.EconomyTransactionEntity;
import com.birditch.raidcore.core.database.entity.PlayerProfileEntity;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.mapping.Table;
import org.junit.jupiter.api.Test;

/// Builds real Hibernate metadata offline (PostgreSQL dialect, no connection)
/// and asserts the physical names DDL generation would use.
class TablePrefixNamingStrategyTest {

  @Test
  void prefixesTableAndIndexNames() {
    Metadata metadata = buildMetadata("smoke_");
    assertEquals(Set.of("smoke_raid_profiles", "smoke_raid_economy_transactions"),
        tableNames(metadata));
    assertEquals(Set.of("smoke_idx_raid_economy_player_time"), indexNames(metadata));
  }

  @Test
  void emptyPrefixKeepsAnnotatedNames() {
    Metadata metadata = buildMetadata("");
    assertEquals(Set.of("raid_profiles", "raid_economy_transactions"), tableNames(metadata));
    assertEquals(Set.of("idx_raid_economy_player_time"), indexNames(metadata));
  }

  private static Metadata buildMetadata(String prefix) {
    StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
        .applySetting("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
        .applySetting("hibernate.boot.allow_jdbc_metadata_access", false)
        .build();
    try {
      TablePrefixNamingStrategy naming = new TablePrefixNamingStrategy(prefix);
      Metadata metadata = new MetadataSources(registry)
          .addAnnotatedClass(PlayerProfileEntity.class)
          .addAnnotatedClass(EconomyTransactionEntity.class)
          .getMetadataBuilder()
          .applyPhysicalNamingStrategy(naming)
          .build();
      naming.prefixIndexNames(metadata);
      return metadata;
    } finally {
      StandardServiceRegistryBuilder.destroy(registry);
    }
  }

  private static Set<String> tableNames(Metadata metadata) {
    return metadata.collectTableMappings().stream()
        .map(Table::getName)
        .collect(Collectors.toSet());
  }

  private static Set<String> indexNames(Metadata metadata) {
    return metadata.collectTableMappings().stream()
        .flatMap(table -> table.getIndexes().values().stream())
        .map(org.hibernate.mapping.Index::getName)
        .collect(Collectors.toSet());
  }
}
