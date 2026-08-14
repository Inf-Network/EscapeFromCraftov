package com.birditch.escapefromcraftov.game.plugin.editor;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import com.birditch.escapefromcraftov.map.ManualCraftovMap;
import com.birditch.escapefromcraftov.map.CraftovMapSnapshot;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Current immutable revisions loaded from administrator-authored map files.
public final class ManualMapRegistry {

  private final Map<String, ManualCraftovMap> maps = new ConcurrentHashMap<>();

  public ManualMapRegistry(ManualMapStore store) {
    Preconditions.checkNotNull(store, "store").loadAll().forEach(this::put);
  }

  public Optional<ManualCraftovMap> find(String mapId) {
    return Optional.ofNullable(maps.get(Preconditions.checkNotNull(mapId, "mapId")));
  }

  public Optional<CraftovMapSnapshot> snapshot(String mapId) {
    return find(mapId).filter(ManualCraftovMap::enabled).filter(map -> map.bounds() != null)
        .map(CraftovMapSnapshot::capture);
  }

  public boolean isEnabled(String mapId) {
    return find(mapId).filter(ManualCraftovMap::enabled).filter(map -> map.bounds() != null).isPresent();
  }

  public Collection<ManualCraftovMap> all() {
    return maps.values().stream().sorted(Comparator.comparing(ManualCraftovMap::mapId)).toList();
  }

  public void put(ManualCraftovMap map) {
    maps.put(Preconditions.checkNotNull(map, "map").mapId(), map);
  }
}
