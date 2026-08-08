package com.birditch.raidcore.loot;

import com.birditch.raidcore.api.loot.LootException;
import com.birditch.raidcore.api.loot.LootTable;
import com.birditch.raidcore.api.loot.LootTableId;
import com.birditch.raidcore.api.loot.LootTableRegistry;
import com.birditch.raidcore.common.check.Preconditions;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/// In-memory [LootTableRegistry] preserving registration order.
///
/// Not thread-safe: registration happens at bootstrap and all access is
/// main-thread confined.
public final class SimpleLootTableRegistry implements LootTableRegistry {

  private final Map<LootTableId, LootTable> tables = new LinkedHashMap<>();

  @Override
  public void register(LootTable table) {
    Preconditions.checkNotNull(table, "table");
    if (tables.containsKey(table.id())) {
      throw new LootException("Loot table already registered: " + table.id().value());
    }
    tables.put(table.id(), table);
  }

  @Override
  public Optional<LootTable> find(LootTableId id) {
    Preconditions.checkNotNull(id, "id");
    return Optional.ofNullable(tables.get(id));
  }

  @Override
  public LootTable require(LootTableId id) {
    return find(id).orElseThrow(() -> new LootException("Loot table not found: " + id.value()));
  }

  @Override
  public Collection<LootTable> all() {
    return Collections.unmodifiableCollection(tables.values());
  }

  @Override
  public int size() {
    return tables.size();
  }
}
