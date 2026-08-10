package com.birditch.raidcore.core.database;

import com.birditch.raidcore.common.check.Preconditions;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategyStandardImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.hibernate.mapping.Index;
import org.hibernate.mapping.Table;

/// Prepends the configured `database.table-prefix` to every physical table
/// name so multiple deployments can share one PostgreSQL schema.
public final class TablePrefixNamingStrategy extends PhysicalNamingStrategyStandardImpl {

  private final String prefix;

  public TablePrefixNamingStrategy(String prefix) {
    Preconditions.checkNotNull(prefix, "prefix");
    this.prefix = prefix;
  }

  @Override
  public Identifier toPhysicalTableName(Identifier logicalName, JdbcEnvironment context) {
    Identifier name = super.toPhysicalTableName(logicalName, context);
    if (prefix.isEmpty()) {
      return name;
    }
    return Identifier.toIdentifier(prefix + name.getText(), name.isQuoted());
  }

  /// Explicit `@Index` names bypass physical naming strategies, yet PostgreSQL
  /// index names share the schema-wide relation namespace with tables. Rename
  /// them here so two prefixed deployments cannot collide on index names.
  public void prefixIndexNames(Metadata metadata) {
    Preconditions.checkNotNull(metadata, "metadata");
    if (prefix.isEmpty()) {
      return;
    }
    for (Table table : metadata.collectTableMappings()) {
      for (Index index : table.getIndexes().values()) {
        index.setName(prefix + index.getName());
      }
    }
  }
}
