package com.birditch.escapefromcraftov.game.plugin.extraction;

import com.birditch.escapefromcraftov.api.extraction.ExtractionPointId;
import com.birditch.escapefromcraftov.api.extraction.ExtractionType;
import com.birditch.escapefromcraftov.api.game.CraftovExtractionArea;
import com.birditch.escapefromcraftov.api.game.CraftovGame;
import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovSpawnLocation;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

/// Main-thread-confined runtime state for team spawns and extraction routes.
///
/// Authored route maps are fail-closed until a team route freezes. Legacy maps
/// remain fail-open before assignment; damaged maps without any reliable
/// unconditional exit enter an explicit degraded fail-open mode so their own
/// extraction conditions remain the final authority instead of trapping players.
public final class TeamExtractionRoutes {

  private final Map<RouteKey, CraftovSpawnLocation> spawns = new HashMap<>();
  private final Map<RouteKey, ResolvedTeamSpawn> resolvedSpawns = new HashMap<>();
  private final Map<RouteKey, Set<ExtractionPointId>> routes = new HashMap<>();
  private final Set<RouteKey> degradedFailOpen = new HashSet<>();

  public Optional<CraftovSpawnLocation> spawn(CraftovGameId gameId, int teamId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
    return Optional.ofNullable(spawns.get(new RouteKey(gameId, teamId)));
  }

  /// Freezes the first authored spawn selected for a team. Later teammates get
  /// the exact same authored point/region even when they resolve a different
  /// safe block inside that region.
  public CraftovSpawnLocation rememberSpawn(
      CraftovGameId gameId, int teamId, CraftovSpawnLocation spawn) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
    Preconditions.checkNotNull(spawn, "spawn");
    return spawns.computeIfAbsent(new RouteKey(gameId, teamId), _ -> spawn);
  }

  /// Freezes the first actual world location reached by this team. Successful
  /// authored resolution and world fallback share this first-wins state, so a
  /// later teammate never reruns a sparse region resolver or splits the squad.
  public ResolvedTeamSpawn rememberResolvedSpawn(CraftovGameId gameId, int teamId,
      double x, double y, double z, float yaw, float pitch, boolean fallback) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
    RouteKey key = new RouteKey(gameId, teamId);
    ResolvedTeamSpawn resolved = resolvedSpawns.computeIfAbsent(key,
        _ -> new ResolvedTeamSpawn(x, y, z, yaw, pitch, fallback));
    if (resolved.fallback()) {
      String routeGroup = "fallback_team_" + teamId;
      spawns.put(key, new CraftovSpawnLocation("fallback_team_" + teamId,
          (int) Math.floor(resolved.x()), (int) Math.floor(resolved.y()),
          (int) Math.floor(resolved.z()), resolved.yaw(), resolved.pitch(), "players", 1.0,
          null, routeGroup));
    }
    return resolved;
  }

  public Optional<ResolvedTeamSpawn> resolvedSpawn(CraftovGameId gameId, int teamId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
    return Optional.ofNullable(resolvedSpawns.get(new RouteKey(gameId, teamId)));
  }

  public Set<String> reservedAuthoredSpawnIds(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    Set<String> result = new HashSet<>();
    spawns.forEach((key, spawn) -> {
      ResolvedTeamSpawn resolved = resolvedSpawns.get(key);
      if (key.gameId().equals(gameId) && (resolved == null || !resolved.fallback())) {
        result.add(spawn.id());
      }
    });
    return Set.copyOf(result);
  }

  /// Freezes the route using the first successfully resolved real spawn block.
  /// A later teleport cannot move the team's assigned extraction.
  public Optional<ExtractionPointId> freezeRoute(
      CraftovGame game, int teamId, double actualSpawnX, double actualSpawnZ) {
    return freezeAutomaticRoute(game, teamId, actualSpawnX, actualSpawnZ)
        .stream().findFirst();
  }

  private Set<ExtractionPointId> freezeAutomaticRoute(
      CraftovGame game, int teamId, double actualSpawnX, double actualSpawnZ) {
    RouteKey key = new RouteKey(game.id(), teamId);
    Set<ExtractionPointId> existing = routes.get(key);
    if (existing != null) return existing;
    if (degradedFailOpen.contains(key)) return Set.of();
    Set<ExtractionPointId> reliable = reliablePrimaryIds(game);
    if (reliable.isEmpty()) {
      degradedFailOpen.add(key);
      return Set.of();
    }
    boolean authoredScopes = hasAuthoredScopes(game);
    if (!authoredScopes) {
      return freezeRoute(game, teamId, actualSpawnX, actualSpawnZ, Set.of());
    }
    String routeGroup = Optional.ofNullable(spawns.get(key))
        .map(CraftovSpawnLocation::routeGroup).orElse("fallback_team_" + teamId);
    Set<ExtractionPointId> matching = new LinkedHashSet<>();
    game.extractionAreas().stream().filter(area -> area.routeScope() != null)
        .filter(area -> area.routeScope().allows(routeGroup))
        .map(CraftovExtractionArea::pointId).forEach(matching::add);
    if (matching.stream().noneMatch(reliable::contains)) {
      Set<ExtractionPointId> safeFallbacks = reliable.stream().filter(pointId ->
          game.extractionAreas().stream().filter(area -> area.pointId().equals(pointId))
              .anyMatch(area -> area.routeScope() == null || area.routeScope().global()))
          .collect(java.util.stream.Collectors.toSet());
      TeamExtractionRoutePolicy.farthest(game.extractionAreas(),
          safeFallbacks.isEmpty() ? reliable : safeFallbacks,
          actualSpawnX, actualSpawnZ, game.id().value() + ':' + teamId)
          .ifPresent(matching::add);
    }
    return freezeRoute(game, teamId, actualSpawnX, actualSpawnZ, matching);
  }

  /// Variant reserved for map-authored spawn-to-exit mappings. A valid
  /// authored point wins; absent or stale mappings use the deterministic
  /// spatial fallback so older map revisions continue to work.
  public Optional<ExtractionPointId> freezeRoute(
      CraftovGame game, int teamId, double actualSpawnX, double actualSpawnZ,
      Optional<ExtractionPointId> authoredPoint) {
    Preconditions.checkNotNull(authoredPoint, "authoredPoint");
    return freezeRoute(game, teamId, actualSpawnX, actualSpawnZ,
        authoredPoint.map(Set::of).orElseGet(Set::of)).stream().findFirst();
  }

  /// Set-valued map-authored override. Multiple valid points remain available
  /// to the team; without one, legacy maps receive one farthest spatial fallback.
  public Set<ExtractionPointId> freezeRoute(
      CraftovGame game, int teamId, double actualSpawnX, double actualSpawnZ,
      Set<ExtractionPointId> authoredPoints) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkArgument(teamId >= 1, "teamId must be at least 1");
    Preconditions.checkNotNull(authoredPoints, "authoredPoints");
    RouteKey key = new RouteKey(game.id(), teamId);
    Set<ExtractionPointId> existing = routes.get(key);
    if (existing != null) return existing;
    if (degradedFailOpen.contains(key)) return Set.of();
    Set<ExtractionPointId> enabledPointIds = new HashSet<>();
    game.extractionPoints().forEach(point -> enabledPointIds.add(point.id()));
    Set<ExtractionPointId> reliablePrimaryIds = reliablePrimaryIds(game);
    Set<ExtractionPointId> validAuthoredPoints = new LinkedHashSet<>();
    game.extractionAreas().stream().map(CraftovExtractionArea::pointId).distinct()
        .filter(enabledPointIds::contains).filter(authoredPoints::contains)
        .forEach(validAuthoredPoints::add);
    if (validAuthoredPoints.isEmpty()) {
      TeamExtractionRoutePolicy.farthest(
            game.extractionAreas(), reliablePrimaryIds.isEmpty()
                ? enabledPointIds : reliablePrimaryIds, actualSpawnX, actualSpawnZ,
            game.id().value() + ':' + teamId).ifPresent(validAuthoredPoints::add);
    }
    if (validAuthoredPoints.isEmpty()) return Set.of();
    Set<ExtractionPointId> selected = java.util.Collections.unmodifiableSet(validAuthoredPoints);
    routes.put(key, selected);
    return selected;
  }

  private static boolean hasAuthoredScopes(CraftovGame game) {
    return game.extractionAreas().stream().anyMatch(area -> area.routeScope() != null);
  }

  private static Set<ExtractionPointId> reliablePrimaryIds(CraftovGame game) {
    Set<ExtractionPointId> areaPointIds = new HashSet<>();
    game.extractionAreas().forEach(area -> areaPointIds.add(area.pointId()));
    Set<ExtractionPointId> reliable = new HashSet<>();
    game.extractionPoints().stream()
        .filter(point -> point.type() == ExtractionType.NORMAL && point.conditions().isEmpty())
        .map(point -> point.id()).filter(areaPointIds::contains).forEach(reliable::add);
    return reliable;
  }

  /// True when the point belongs to the player's team. Authored maps deny before
  /// freeze; only legacy compatibility or explicit degraded state is fail-open.
  public boolean canUse(CraftovGame game, UUID playerId, ExtractionPointId pointId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
    OptionalInt teamId = teamId(game, playerId);
    if (teamId.isEmpty()) return !hasAuthoredScopes(game);
    RouteKey key = new RouteKey(game.id(), teamId.getAsInt());
    Set<ExtractionPointId> assigned = routes.get(key);
    if (assigned != null) return assigned.contains(pointId);
    if (degradedFailOpen.contains(key)) return true;
    return !hasAuthoredScopes(game);
  }

  /// Extraction areas visible to this player. Once a route exists this exposes
  /// only the team's assigned exit; old rooms without route coordinates expose
  /// their original complete list.
  public List<CraftovExtractionArea> visibleAreas(CraftovGame game, UUID playerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    OptionalInt teamId = teamId(game, playerId);
    if (teamId.isEmpty()) return hasAuthoredScopes(game) ? List.of() : game.extractionAreas();
    RouteKey key = new RouteKey(game.id(), teamId.getAsInt());
    Set<ExtractionPointId> assigned = routes.get(key);
    if (assigned == null) {
      return degradedFailOpen.contains(key) || !hasAuthoredScopes(game)
          ? game.extractionAreas() : List.of();
    }
    return game.extractionAreas().stream()
        .filter(area -> assigned.contains(area.pointId())).toList();
  }

  public Optional<ExtractionPointId> assignedPoint(CraftovGame game, UUID playerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    return assignedPoints(game, playerId).stream().findFirst();
  }

  public Set<ExtractionPointId> assignedPoints(CraftovGame game, UUID playerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    OptionalInt teamId = teamId(game, playerId);
    if (teamId.isEmpty()) return Set.of();
    return routes.getOrDefault(new RouteKey(game.id(), teamId.getAsInt()), Set.of());
  }

  /// Drops abandoned team state after a leave/extraction without disturbing
  /// routes still used by another living teammate.
  public void prune(CraftovGame game) {
    Preconditions.checkNotNull(game, "game");
    Set<Integer> activeTeams = new HashSet<>();
    game.teams().stream().filter(team -> team.size() > 0)
        .forEach(team -> activeTeams.add(team.teamId()));
    spawns.keySet().removeIf(key -> key.gameId().equals(game.id())
        && !activeTeams.contains(key.teamId()));
    resolvedSpawns.keySet().removeIf(key -> key.gameId().equals(game.id())
        && !activeTeams.contains(key.teamId()));
    routes.keySet().removeIf(key -> key.gameId().equals(game.id())
        && !activeTeams.contains(key.teamId()));
    degradedFailOpen.removeIf(key -> key.gameId().equals(game.id())
        && !activeTeams.contains(key.teamId()));
  }

  public void clear(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    spawns.keySet().removeIf(key -> key.gameId().equals(gameId));
    resolvedSpawns.keySet().removeIf(key -> key.gameId().equals(gameId));
    routes.keySet().removeIf(key -> key.gameId().equals(gameId));
    degradedFailOpen.removeIf(key -> key.gameId().equals(gameId));
  }

  private static OptionalInt teamId(CraftovGame game, UUID playerId) {
    return game.players().stream().filter(player -> player.playerId().equals(playerId))
        .findFirst().map(player -> player.teamId()).orElseGet(OptionalInt::empty);
  }

  private record RouteKey(CraftovGameId gameId, int teamId) {}

  public record ResolvedTeamSpawn(
      double x, double y, double z, float yaw, float pitch, boolean fallback) {

    public ResolvedTeamSpawn {
      Preconditions.checkArgument(Double.isFinite(x), "x must be finite");
      Preconditions.checkArgument(Double.isFinite(y), "y must be finite");
      Preconditions.checkArgument(Double.isFinite(z), "z must be finite");
      Preconditions.checkArgument(Float.isFinite(yaw), "yaw must be finite");
      Preconditions.checkArgument(Float.isFinite(pitch), "pitch must be finite");
    }
  }
}
