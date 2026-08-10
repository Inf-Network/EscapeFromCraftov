package com.birditch.raidcore.game.plugin.editor;

import com.birditch.raidcore.common.check.Preconditions;
import com.birditch.raidcore.map.ManualRaidMap;
import com.birditch.raidcore.map.RaidMapSnapshot;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/// Current immutable revisions loaded from administrator-authored map files.
public final class ManualMapRegistry {

  private final Map<String, ManualRaidMap> maps = new ConcurrentHashMap<>();

  public ManualMapRegistry(ManualMapStore store) {
    Preconditions.checkNotNull(store, "store").loadAll().forEach(this::put);
  }

  public Optional<ManualRaidMap> find(String mapId) {
    return Optional.ofNullable(maps.get(Preconditions.checkNotNull(mapId, "mapId")));
  }

  public Optional<RaidMapSnapshot> snapshot(String mapId) {
    return find(mapId).filter(ManualRaidMap::enabled).filter(map -> map.bounds() != null)
        .map(RaidMapSnapshot::capture);
  }

  public boolean isEnabled(String mapId) {
    return find(mapId).filter(ManualRaidMap::enabled).filter(map -> map.bounds() != null).isPresent();
  }

  public Collection<ManualRaidMap> all() {
    return maps.values().stream().sorted(Comparator.comparing(ManualRaidMap::mapId)).toList();
  }

  public void put(ManualRaidMap map) {
    maps.put(Preconditions.checkNotNull(map, "map").mapId(), map);
  }
}
