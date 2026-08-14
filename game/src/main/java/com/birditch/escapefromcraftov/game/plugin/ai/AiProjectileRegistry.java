package com.birditch.escapefromcraftov.game.plugin.ai;

import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Main-thread registry used to remove still-flying projectiles when a game ends. */
final class AiProjectileRegistry {

  private final Map<String, LinkedHashSet<UUID>> byGame = new HashMap<>();
  private final LinkedHashMap<UUID, String> live = new LinkedHashMap<>();

  void track(String gameId, UUID projectileId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(projectileId, "projectileId");
    String previousGame = live.put(projectileId, gameId);
    if (previousGame != null && !previousGame.equals(gameId)) {
      removeFromGame(previousGame, projectileId);
    }
    byGame.computeIfAbsent(gameId, _ -> new LinkedHashSet<>()).add(projectileId);
  }

  boolean untrack(String gameId, UUID projectileId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkNotNull(projectileId, "projectileId");
    if (!gameId.equals(live.get(projectileId))) return false;
    live.remove(projectileId);
    removeFromGame(gameId, projectileId);
    return true;
  }

  /** Round-robin validation with a hard per-call lookup budget. */
  PruneResult prune(int maximumChecks, Predicate<UUID> valid) {
    Preconditions.checkArgument(maximumChecks >= 0, "maximumChecks must not be negative");
    Preconditions.checkNotNull(valid, "valid");
    int checks = Math.min(maximumChecks, live.size());
    int removed = 0;
    for (int index = 0; index < checks; index++) {
      Map.Entry<UUID, String> entry = live.entrySet().iterator().next();
      UUID projectileId = entry.getKey();
      String gameId = entry.getValue();
      live.remove(projectileId);
      boolean keep;
      try {
        keep = valid.test(projectileId);
      } catch (RuntimeException ignored) {
        keep = false;
      }
      if (keep) {
        live.put(projectileId, gameId);
      } else {
        removeFromGame(gameId, projectileId);
        removed++;
      }
    }
    return new PruneResult(checks, removed);
  }

  List<UUID> drain(String gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    LinkedHashSet<UUID> projectiles = byGame.remove(gameId);
    if (projectiles == null) return List.of();
    for (UUID projectileId : projectiles) {
      if (gameId.equals(live.get(projectileId))) live.remove(projectileId);
    }
    return List.copyOf(projectiles);
  }

  int size() {
    return live.size();
  }

  private void removeFromGame(String gameId, UUID projectileId) {
    LinkedHashSet<UUID> projectiles = byGame.get(gameId);
    if (projectiles == null) return;
    projectiles.remove(projectileId);
    if (projectiles.isEmpty()) byGame.remove(gameId);
  }

  record PruneResult(int checked, int removed) {}
}
