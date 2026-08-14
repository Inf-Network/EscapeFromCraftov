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
/// A route only becomes authoritative after a valid map extraction area has
/// been selected. Missing legacy spatial data therefore remains fail-open,
/// while a real frozen route is fail-closed for every other exit.
public final class TeamExtractionRoutes {

  private final Map<RouteKey, CraftovSpawnLocation> spawns = new HashMap<>();
  private final Map<RouteKey, ResolvedTeamSpawn> resolvedSpawns = new HashMap<>();
  private final Map<RouteKey, Set<ExtractionPointId>> routes = new HashMap<>();

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
      spawns.put(key, new CraftovSpawnLocation("fallback_team_" + teamId,
          (int) Math.floor(resolved.x()), (int) Math.floor(resolved.y()),
          (int) Math.floor(resolved.z()), resolved.yaw(), resolved.pitch()));
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
    return freezeRoute(game, teamId, actualSpawnX, actualSpawnZ, Set.of())
        .stream().findFirst();
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
    Set<ExtractionPointId> enabledPointIds = new HashSet<>();
    game.extractionPoints().forEach(point -> enabledPointIds.add(point.id()));
    Set<ExtractionPointId> areaPointIds = new HashSet<>();
    game.extractionAreas().forEach(area -> areaPointIds.add(area.pointId()));
    Set<ExtractionPointId> reliablePrimaryIds = new HashSet<>();
    game.extractionPoints().stream()
        .filter(point -> point.type() == ExtractionType.NORMAL && point.conditions().isEmpty())
        .map(point -> point.id()).filter(areaPointIds::contains)
        .forEach(reliablePrimaryIds::add);
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

  /// True when the point belongs to the player's team. If this room has no
  /// valid spatial route for that team, returns true for legacy compatibility.
  public boolean canUse(CraftovGame game, UUID playerId, ExtractionPointId pointId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    Preconditions.checkNotNull(pointId, "pointId");
    OptionalInt teamId = teamId(game, playerId);
    if (teamId.isEmpty()) return true;
    Set<ExtractionPointId> assigned = routes.get(new RouteKey(game.id(), teamId.getAsInt()));
    return assigned == null || assigned.contains(pointId);
  }

  /// Extraction areas visible to this player. Once a route exists this exposes
  /// only the team's assigned exit; old rooms without route coordinates expose
  /// their original complete list.
  public List<CraftovExtractionArea> visibleAreas(CraftovGame game, UUID playerId) {
    Preconditions.checkNotNull(game, "game");
    Preconditions.checkNotNull(playerId, "playerId");
    OptionalInt teamId = teamId(game, playerId);
    if (teamId.isEmpty()) return game.extractionAreas();
    Set<ExtractionPointId> assigned = routes.get(new RouteKey(game.id(), teamId.getAsInt()));
    if (assigned == null) return game.extractionAreas();
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
  }

  public void clear(CraftovGameId gameId) {
    Preconditions.checkNotNull(gameId, "gameId");
    spawns.keySet().removeIf(key -> key.gameId().equals(gameId));
    resolvedSpawns.keySet().removeIf(key -> key.gameId().equals(gameId));
    routes.keySet().removeIf(key -> key.gameId().equals(gameId));
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
