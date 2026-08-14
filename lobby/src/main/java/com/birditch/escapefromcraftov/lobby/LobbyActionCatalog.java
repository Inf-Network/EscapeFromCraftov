package com.birditch.escapefromcraftov.lobby;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidDifficulty;
import com.birditch.escapefromcraftov.api.game.RaidMode;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.MapActionTierCatalog;
import com.birditch.escapefromcraftov.api.network.NetworkCapabilities;
import com.birditch.escapefromcraftov.common.check.Preconditions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Deterministic Lobby view of action tiers advertised by live Game servers. */
public final class LobbyActionCatalog {

  private static final RaidActionProfile.LegacyAlias DEFAULT_ALIAS =
      new RaidActionProfile.LegacyAlias(RaidMode.NORMAL, RaidDifficulty.NORMAL);

  private final Map<CraftovMapId, List<RaidActionProfile>> tiersByMap;
  private final Set<CraftovMapId> canonicalMaps;

  private LobbyActionCatalog(Map<CraftovMapId, List<RaidActionProfile>> tiersByMap,
      Set<CraftovMapId> canonicalMaps) {
    this.tiersByMap = Map.copyOf(tiersByMap);
    this.canonicalMaps = Set.copyOf(canonicalMaps);
  }

  public static LobbyActionCatalog empty() {
    return new LobbyActionCatalog(Map.of(), Set.of());
  }

  /**
   * Intersects exact descriptors advertised for the same map. A tier that is
   * missing or differs on one advertising server is hidden, so heartbeat
   * timing and Redis iteration order can never make the UI oscillate.
   */
  public static LobbyActionCatalog fromServers(List<GameServerSnapshot> servers) {
    Preconditions.checkNotNull(servers, "servers");
    List<GameServerSnapshot> ordered = servers.stream()
        .filter(server -> server.supports(NetworkCapabilities.ACTION_TIER_CATALOG_V1))
        .sorted(Comparator.comparing(server -> server.serverId().value()))
        .toList();
    LinkedHashMap<CraftovMapId, List<List<RaidActionProfile>>> advertised = new LinkedHashMap<>();
    for (GameServerSnapshot server : ordered) {
      for (MapActionTierCatalog catalog : server.actionTierCatalog()) {
        advertised.computeIfAbsent(catalog.mapId(), _ -> new ArrayList<>()).add(catalog.tiers());
      }
    }
    LinkedHashMap<CraftovMapId, List<RaidActionProfile>> result = new LinkedHashMap<>();
    advertised.forEach((mapId, catalogs) -> {
      List<RaidActionProfile> common = catalogs.getFirst().stream()
          .filter(profile -> catalogs.stream().skip(1)
              .allMatch(candidate -> candidate.contains(profile)))
          .toList();
      if (!common.isEmpty()) result.put(mapId, common);
    });
    return new LobbyActionCatalog(result, advertised.keySet());
  }

  public List<RaidActionProfile> tiers(CraftovMapId mapId) {
    return tiersByMap.getOrDefault(Preconditions.checkNotNull(mapId, "mapId"), List.of());
  }

  public Optional<RaidActionProfile> find(CraftovMapId mapId, RaidTierId tierId) {
    Preconditions.checkNotNull(tierId, "tierId");
    return tiers(mapId).stream().filter(tier -> tier.id().equals(tierId)).findFirst();
  }

  public Set<CraftovMapId> advertisedMaps() {
    return canonicalMaps;
  }

  /** True even when conflicting server descriptors forced every tier to be hidden. */
  public boolean requiresCanonical(CraftovMapId mapId) {
    return canonicalMaps.contains(Preconditions.checkNotNull(mapId, "mapId"));
  }

  public Optional<RaidActionProfile> resolveLegacy(CraftovMapId mapId,
      RaidMode mode, RaidDifficulty difficulty) {
    RaidActionProfile.LegacyAlias requested = new RaidActionProfile.LegacyAlias(
        Preconditions.checkNotNull(mode, "mode"),
        Preconditions.checkNotNull(difficulty, "difficulty"));
    return tiers(mapId).stream()
        .filter(profile -> profile.legacyAliases().contains(requested))
        .findFirst();
  }

  public List<CraftovMapId> availableMaps(LobbyConfig config) {
    Preconditions.checkNotNull(config, "config");
    LinkedHashSet<CraftovMapId> result = new LinkedHashSet<>(config.enabledMaps());
    advertisedMaps().stream().sorted(Comparator.comparing(CraftovMapId::value))
        .forEach(result::add);
    return List.copyOf(result);
  }

  /** First authored alias is the stable compatibility path; empty means normal/normal. */
  public static RaidActionProfile.LegacyAlias legacyAlias(RaidActionProfile profile) {
    Preconditions.checkNotNull(profile, "profile");
    return profile.legacyAliases().isEmpty() ? DEFAULT_ALIAS : profile.legacyAliases().getFirst();
  }

  public List<RaidTierId> tierIds(CraftovMapId mapId) {
    List<RaidTierId> ids = new ArrayList<>();
    for (RaidActionProfile profile : tiers(mapId)) ids.add(profile.id());
    return List.copyOf(ids);
  }
}
