package com.birditch.escapefromcraftov.game.plugin.instance;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Links craftov games to their instance worlds and tracks per-player isolation
/// groups.
///
/// Threading: the game↔world maps and the ready set are MAIN-THREAD confined.
/// The player→group map is maintained on the main thread but backed by a
/// [ConcurrentHashMap] so async code (chat isolation) may read it via
/// [#groupOf(UUID)] at any time. Groups are `"game:<gameId>"` inside a craftov
/// and `"lobby"` otherwise.
public final class InstanceWorldIndex {

  private static final String DEFAULT_GROUP = "lobby";

  private final Map<CraftovGameId, String> worldsByGame = new HashMap<>();
  private final Map<String, CraftovGameId> gamesByWorld = new HashMap<>();
  private final Set<CraftovGameId> ready = new HashSet<>();
  private final ConcurrentHashMap<UUID, String> groupByPlayer = new ConcurrentHashMap<>();

  public void register(CraftovGameId gameId, String worldName) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(worldName, "worldName");
    Preconditions.checkArgument(
        !worldsByGame.containsKey(gameId), "Game already registered: " + gameId.value());
    worldsByGame.put(gameId, worldName);
    gamesByWorld.put(worldName, gameId);
  }

  public void markReady(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkState(
        worldsByGame.containsKey(gameId), "Game not registered: " + gameId.value());
    ready.add(gameId);
  }

  public boolean isReady(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    return ready.contains(gameId);
  }

  public Optional<String> worldOf(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    return Optional.ofNullable(worldsByGame.get(gameId));
  }

  public Optional<CraftovGameId> gameByWorld(String worldName) {
    Preconditions.checkNotNull(worldName, "worldName");
    return Optional.ofNullable(gamesByWorld.get(worldName));
  }

  /// Snapshot of every room with a registered world. Used during orderly
  /// shutdown because the domain manager is intentionally independent from
  /// Bukkit world deletion.
  public List<CraftovGameId> gameIds() {
    return List.copyOf(worldsByGame.keySet());
  }

  /// Removes every trace of `gameId`; unknown ids are ignored so removal is
  /// idempotent.
  public void remove(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    String worldName = worldsByGame.remove(gameId);
    if (worldName != null) {
      gamesByWorld.remove(worldName);
    }
    ready.remove(gameId);
  }

  public void setGroup(UUID playerId, String group) {
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(group, "group");
    groupByPlayer.put(playerId, group);
  }

  /// Safe to call from any thread; unknown players default to `"lobby"`.
  public String groupOf(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    return groupByPlayer.getOrDefault(playerId, DEFAULT_GROUP);
  }

  public void clearGroup(UUID playerId) {
    Preconditions.checkNotNull(playerId, "playerId");
    groupByPlayer.remove(playerId);
  }
}
