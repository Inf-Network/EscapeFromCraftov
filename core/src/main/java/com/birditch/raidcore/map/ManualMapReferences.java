package com.birditch.raidcore.map;

import com.birditch.raidcore.common.check.Preconditions;
import java.util.Set;

/// External references that the pure map validator cannot discover from Bukkit.
public record ManualMapReferences(
    boolean worldExists,
    Set<String> lootTables,
    Set<String> itemIds) {

  public ManualMapReferences {
    lootTables = Set.copyOf(Preconditions.checkNotNull(lootTables, "lootTables"));
    itemIds = Set.copyOf(Preconditions.checkNotNull(itemIds, "itemIds"));
  }

  public static ManualMapReferences allowing(String... lootTables) {
    return new ManualMapReferences(true, Set.of(lootTables), Set.of());
  }
}
