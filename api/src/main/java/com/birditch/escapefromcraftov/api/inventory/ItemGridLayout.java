package com.birditch.escapefromcraftov.api.inventory;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistable collection of pocket, rig, backpack, or other cargo grids. */
public record ItemGridLayout(Map<String, ItemGrid> grids) {

  public ItemGridLayout {
    Map<String, ItemGrid> copy = new LinkedHashMap<>();
    Set<UUID> identities = new java.util.HashSet<>();
    for (Map.Entry<String, ItemGrid> entry
        : Preconditions.checkNotNull(grids, "grids").entrySet()) {
      String id = Preconditions.checkNotNull(entry.getKey(), "grid id");
      Preconditions.checkArgument(!id.isBlank(), "grid id must not be blank");
      ItemGrid grid = Preconditions.checkNotNull(entry.getValue(), "grid");
      Preconditions.checkArgument(copy.putIfAbsent(id, grid) == null,
          "duplicate grid id");
      for (GridItemPlacement placement : grid.placements()) {
        Preconditions.checkArgument(identities.add(placement.item().instanceId()),
            "duplicate physical item instance across grids");
      }
    }
    grids = Collections.unmodifiableMap(copy);
  }

  public Optional<ItemGrid> find(String gridId) {
    Preconditions.checkNotNull(gridId, "gridId");
    return Optional.ofNullable(grids.get(gridId));
  }
}
