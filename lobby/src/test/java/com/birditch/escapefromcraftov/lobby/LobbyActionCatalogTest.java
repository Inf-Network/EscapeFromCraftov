package com.birditch.escapefromcraftov.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.birditch.escapefromcraftov.api.game.CraftovMapId;
import com.birditch.escapefromcraftov.api.game.LegacyRaidTierResolver;
import com.birditch.escapefromcraftov.api.game.RaidActionProfile;
import com.birditch.escapefromcraftov.api.game.RaidTierId;
import com.birditch.escapefromcraftov.api.network.GameServerSnapshot;
import com.birditch.escapefromcraftov.api.network.MapActionTierCatalog;
import com.birditch.escapefromcraftov.api.network.NetworkCapabilities;
import com.birditch.escapefromcraftov.api.network.ServerId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LobbyActionCatalogTest {

  private static final CraftovMapId MAP = CraftovMapId.of("test_flat");

  @Test
  void ignoresHeartbeatAgeAndKeepsOnlyExactProfilesCommonToAdvertisingServers() {
    List<RaidActionProfile> defaults = LegacyRaidTierResolver.defaultProfiles();
    RaidActionProfile changedTopSecret = new RaidActionProfile(RaidTierId.of("top_secret"),
        "绝密行动", 99_999, 2, 3, 5_000, defaults.getLast().legacyAliases());
    GameServerSnapshot lexicallyFirstButOlder = server("game-a", 1,
        List.of(defaults.get(0), defaults.get(1), defaults.get(2)));
    GameServerSnapshot newer = server("game-b", 999,
        List.of(defaults.get(0), defaults.get(1), changedTopSecret));

    LobbyActionCatalog catalog = LobbyActionCatalog.fromServers(
        List.of(newer, lexicallyFirstButOlder));
    LobbyActionCatalog reversed = LobbyActionCatalog.fromServers(
        List.of(lexicallyFirstButOlder, newer));

    assertEquals(List.of(defaults.get(0), defaults.get(1)), catalog.tiers(MAP));
    assertEquals(catalog.tiers(MAP), reversed.tiers(MAP));
    assertTrue(catalog.find(MAP, RaidTierId.of("top_secret")).isEmpty());
    assertTrue(catalog.requiresCanonical(MAP));
    assertEquals(defaults.getFirst(), catalog.resolveLegacy(MAP,
        com.birditch.escapefromcraftov.api.game.RaidMode.NORMAL,
        com.birditch.escapefromcraftov.api.game.RaidDifficulty.NORMAL).orElseThrow());
  }

  @Test
  void ignoresCatalogFieldsFromServersThatDoNotDeclareTheCapability() {
    GameServerSnapshot oldServer = new GameServerSnapshot(ServerId.of("game-old"),
        "craftov-old", 1, 4, List.of(), Set.of(),
        List.of(new MapActionTierCatalog(MAP, LegacyRaidTierResolver.defaultProfiles())));

    assertTrue(LobbyActionCatalog.fromServers(List.of(oldServer)).tiers(MAP).isEmpty());
  }

  @Test
  void conflictingCanonicalMapStaysMarkedAndCannotSilentlyBecomeLegacy() {
    List<RaidActionProfile> defaults = LegacyRaidTierResolver.defaultProfiles();
    List<RaidActionProfile> changed = defaults.stream().map(profile -> new RaidActionProfile(
        profile.id(), profile.displayName(), profile.minimumLoadoutValue() + 1,
        profile.aiMultiplier(), profile.lootMultiplier(), profile.entryFee(),
        profile.legacyAliases())).toList();

    LobbyActionCatalog catalog = LobbyActionCatalog.fromServers(List.of(
        server("game-a", 1, defaults), server("game-b", 2, changed)));

    assertTrue(catalog.requiresCanonical(MAP));
    assertTrue(catalog.tiers(MAP).isEmpty());
    assertTrue(catalog.resolveLegacy(MAP,
        com.birditch.escapefromcraftov.api.game.RaidMode.NORMAL,
        com.birditch.escapefromcraftov.api.game.RaidDifficulty.NORMAL).isEmpty());
  }

  private static GameServerSnapshot server(String id, long heartbeat,
      List<RaidActionProfile> profiles) {
    return new GameServerSnapshot(ServerId.of(id), id, heartbeat, 4, List.of(),
        Set.of(NetworkCapabilities.ACTION_TIER_CATALOG_V1),
        List.of(new MapActionTierCatalog(MAP, profiles)));
  }
}
