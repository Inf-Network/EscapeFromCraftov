package com.birditch.escapefromcraftov.game.plugin.loot;

import com.birditch.escapefromcraftov.api.game.CraftovGameId;
import com.birditch.escapefromcraftov.api.game.CraftovLootPoint;
import com.birditch.escapefromcraftov.api.game.CraftovLootZone;
import com.birditch.escapefromcraftov.api.loot.LootContainerType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Converts authored Loot Zones into stable per-block containers for one raid. */
final class LootZoneResolver {

  private static final double TWO_TO_53 = 9_007_199_254_740_992.0;

  private LootZoneResolver() {}

  static Optional<CraftovLootPoint> resolve(CraftovGameId gameId, List<CraftovLootZone> zones,
      int x, int y, int z, LootContainerType containerType) {
    return zones.stream().filter(zone -> zone.contains(x, y, z))
        .filter(zone -> active(gameId, zone, x, y, z))
        .findFirst()
        .map(zone -> new CraftovLootPoint("zone:" + zone.id() + ':' + x + ':' + y + ':' + z,
            x, y, z, containerType, zone.lootTableId(), zone.tier(), false));
  }

  static boolean contains(List<CraftovLootZone> zones, int x, int y, int z) {
    return zones.stream().anyMatch(zone -> zone.contains(x, y, z));
  }

  static boolean active(CraftovGameId gameId, CraftovLootZone zone, int x, int y, int z) {
    if (zone.density() >= 1) return true;
    byte[] seed = (gameId.value() + '|' + zone.id() + '|' + x + '|' + y + '|' + z)
        .getBytes(StandardCharsets.UTF_8);
    long bits = UUID.nameUUIDFromBytes(seed).getMostSignificantBits() >>> 11;
    return bits / TWO_TO_53 < zone.density();
  }
}
